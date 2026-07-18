/*
 * Copyright 2026 Jason Harrop
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* Admin panel. Login is the shared PKCE flow (../shared/auth.js)
 * against the Keycloak `web` public client; this file is the panel's UI
 * only. App-specific admin sections (content stores, moderation, ...)
 * grow in here beside the Users section.
 */
"use strict";

// ---- UI -----------------------------------------------------------------

const statusBox = document.getElementById("status");
const message = document.getElementById("message");
let adminEmail = ""; // best-effort default for the test-send prompt (whoami has no email today)

Auth.onFresh401 = (text) => {
    statusBox.textContent = text;
    statusBox.className = "warn";
};

function say(text, isError) {
    message.textContent = text;
    message.className = isError ? "error" : "";
    // a native modal greys out and inerts the page behind it — including the
    // header line above. Mirror the message into the topmost open dialog, or
    // a 409 on Save reads as "nothing happened".
    const open = document.querySelectorAll("dialog[open]");
    const dialog = open.length ? open[open.length - 1] : null;
    if (dialog) {
        let box = dialog.querySelector(".dialog-message");
        if (!box) {
            box = document.createElement("div");
            box.className = "dialog-message";
            (dialog.querySelector("article") || dialog).prepend(box);
        }
        box.textContent = text;
        box.classList.toggle("error", !!isError);
    }
}

// the edit forms and detail panels are <dialog> elements — open them as
// native modals (backdrop, Esc-to-close, focus trap), so no scroll-into-view
// juggling and no fighting the [hidden] rule. close() reverses it.
// openDialog is idempotent: openMembership/openHousehold re-open to refresh in
// place, and showModal() on an already-open dialog throws.
function openDialog(id) {
    const d = document.getElementById(id);
    // don't let a previous visit's error greet the next open
    const box = d.querySelector(".dialog-message");
    if (box) {
        box.textContent = "";
        box.classList.remove("error");
    }
    if (!d.open) d.showModal();
}
function closeDialog(id) {
    const d = document.getElementById(id);
    if (d.open) d.close();
}

async function showIdentity() {
    const response = await Auth.api("/whoami");
    if (!response) return false; // redirected to login, or mismatch shown
    const who = await response.json();
    if (!who.roles.includes("admin")) {
        // Bearer auth means the server can't role-gate this static page
        // (writes already 403 there); gate here instead — non-admins go
        // to the user webapp rather than a panel that can't work.
        location.replace("../web/");
        return false;
    }
    statusBox.textContent = `Logged in as ${who.username} — roles: ${who.roles.join(", ") || "(none)"}`;
    statusBox.className = "";
    const out = document.createElement("button");
    out.textContent = "Log out";
    out.style.marginLeft = "1rem";
    out.onclick = Auth.logout;
    statusBox.appendChild(out);
    return true;
}

// ---- users (claims, verified flag, manager grant) -------------------------

const CLAIMABLE = ["member", "other"]; // keep in sync with KeycloakAdmin.CLAIMABLE

async function userAction(path, body) {
    const response = await Auth.api(path, {
        method: "PUT",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify(body),
    });
    if (!response) return false;
    if (!response.ok) {
        const detail = await response.text();
        say(`Update refused (HTTP ${response.status}): ${detail}`, true);
        return false;
    }
    return true;
}

async function renderUsers() {
    const search = document.getElementById("userSearch").value.trim();
    const params = new URLSearchParams({max: "50"});
    if (search) params.set("search", search);
    const response = await Auth.api(`/admin/users?${params}`);
    if (!response || !response.ok) return;
    const users = await response.json();
    const body = document.querySelector("#users tbody");
    body.innerHTML = "";
    for (const u of users) {
        const row = body.insertRow();
        row.insertCell().textContent = u.username;
        row.insertCell().textContent = `${u.firstName} ${u.lastName}`.trim();
        row.insertCell().textContent = u.email;

        // claimed role: a select the admin can correct (server re-syncs grants)
        const claimCell = row.insertCell();
        const claim = document.createElement("select");
        for (const [value, label] of [["", "(none)"], ...CLAIMABLE.map(r => [r, r])]) {
            const option = document.createElement("option");
            option.value = value;
            option.textContent = label;
            option.selected = (u.claimed_role || "") === value;
            claim.appendChild(option);
        }
        claim.onchange = async () => {
            if (await userAction(`/admin/users/${u.id}/claim`,
                    {role: claim.value || null})) {
                say(`${u.username}: claim ${claim.value || "cleared"} (verified reset).`);
            }
            renderUsers();
        };
        claimCell.appendChild(claim);

        // verified: a colored badge that doubles as the toggle. Meaningful
        // only alongside a claim — a claimless user gets a muted, disabled dash.
        const verifiedCell = row.insertCell();
        const verified = document.createElement("button");
        verified.type = "button";
        if (!u.claimed_role) {
            verified.className = "badge badge-grey";
            verified.textContent = "—";
            verified.disabled = true;
            verified.title = "No claim to verify";
        } else {
            verified.className = u.verified ? "badge badge-green" : "badge badge-amber";
            verified.textContent = u.verified ? "Verified" : "Unverified";
            verified.title = "Whether the claim has been checked as fact — click to toggle";
            verified.onclick = async () => {
                if (await userAction(`/admin/users/${u.id}/verified`, {verified: !u.verified})) {
                    say(`${u.username}: ${!u.verified ? "verified" : "verification removed"}.`);
                }
                renderUsers();
            };
        }
        verifiedCell.appendChild(verified);

        // manager: the admin-only role
        const managerCell = row.insertCell();
        const manager = document.createElement("input");
        manager.type = "checkbox";
        manager.checked = u.roles.includes("manager");
        manager.onchange = async () => {
            if (await userAction(`/admin/users/${u.id}/manager`,
                    {granted: manager.checked})) {
                say(`${u.username}: manager ${manager.checked ? "granted" : "revoked"}.`);
            }
            renderUsers();
        };
        managerCell.appendChild(manager);

        row.insertCell().textContent = u.roles.join(", ") || "—";
    }
    document.getElementById("usersSection").hidden = false;
}

// ---- self-serve provisioning (CR-006) --------------------------------------

// each action names a different admin fix — never collapsed into "skipped"
const SS_ACTIONS = {
    CREATE: ["Create account", "green"],
    ADOPT: ["Adopt existing account", "green"],
    ALREADY_LINKED: ["Already linked", "grey"],
    SHARED_ADDRESS: ["Shared address", "grey"],
    CONFLICT_HOUSEHOLDS: ["Conflict: two households", "amber"],
    CONFLICT_SUBJECT: ["Conflict: account linked elsewhere", "amber"],
    SKIPPED_UNVERIFIED: ["Skipped: unverified account", "amber"],
    ERROR: ["Error", "amber"],
};

async function runSelfServe(apply) {
    const response = await registerCall(
        `/admin/self-serve/${apply ? "provision" : "preview"}`, {method: "POST"});
    if (!response) return;
    const report = await response.json();
    const counts = Object.entries(report.counts)
        .map(([k, v]) => `${(SS_ACTIONS[k] || [k])[0]}: ${v}`).join(" · ");
    document.getElementById("ssCounts").textContent =
        (apply ? "Provisioned. " : "Preview — nothing written. ") + (counts || "No candidates.");
    const table = document.getElementById("ssReport");
    const body = table.querySelector("tbody");
    body.innerHTML = "";
    for (const r of report.rows) {
        const row = body.insertRow();
        row.insertCell().textContent = `${r.name} (#${r.personId})`;
        row.insertCell().textContent = r.email;
        const [label, colour] = SS_ACTIONS[r.action] || [r.action, "grey"];
        const badge = document.createElement("span");
        badge.className = `badge badge-${colour}`;
        badge.textContent = label;
        row.insertCell().appendChild(badge);
        row.insertCell().textContent = r.detail || "";
    }
    table.hidden = report.rows.length === 0;
    // Provision is gated on a preview (the import pattern); a run that would
    // do nothing keeps it disabled
    const actionable = (report.counts.CREATE || 0) + (report.counts.ADOPT || 0);
    document.getElementById("ssProvision").disabled = apply || actionable === 0;
    if (apply) {
        say("Provisioning done — new accounts appear in the users list above.");
        renderUsers();
    }
}

function wireSelfServe() {
    document.getElementById("ssPreview").onclick = () => runSelfServe(false);
    document.getElementById("ssProvision").onclick = () => runSelfServe(true);
}

// ---- register: people ------------------------------------------------------

let editingPersonId = null; // null = the form creates; otherwise it updates

async function registerCall(path, options) {
    const response = await Auth.api(path, options);
    if (!response) return null;
    if (!response.ok) {
        const detail = await response.text();
        say(`Refused (HTTP ${response.status}): ${detail}`, true);
        return null;
    }
    return response;
}

async function renderPeople() {
    const q = document.getElementById("personSearch").value.trim();
    const params = new URLSearchParams({limit: "50"});
    if (q) params.set("q", q);
    const response = await registerCall(`/admin/people?${params}`);
    if (!response) return;
    const page = await response.json();
    const body = document.querySelector("#people tbody");
    body.innerHTML = "";
    for (const p of page.people) {
        const row = body.insertRow();
        row.insertCell().textContent = p.id;
        row.insertCell().textContent =
            [p.title, p.givenName, p.familyName].filter(Boolean).join(" ")
            + (p.preferredName ? ` (${p.preferredName})` : "");
        row.insertCell().textContent =
            p.emails.map(e => e.email + (e.isPrimary ? "*" : "")).join(", ");
        row.insertCell().textContent =
            p.phones.map(ph => ph.number + (ph.type ? ` (${ph.type})` : "")).join(", ");
        row.insertCell().textContent = p.notes || "";
        const edit = document.createElement("button");
        edit.textContent = "Edit";
        edit.onclick = () => openPersonForm(p);
        row.insertCell().appendChild(edit);
    }
    document.getElementById("peopleTotal").textContent = `${page.total} match(es)`;
}

function openPersonForm(person) {
    editingPersonId = person ? person.id : null;
    document.getElementById("personFormTitle").textContent =
        person ? `Edit person #${person.id}` : "New person";
    document.getElementById("pfTitle").value = person?.title || "";
    document.getElementById("pfGiven").value = person?.givenName || "";
    document.getElementById("pfFamily").value = person?.familyName || "";
    document.getElementById("pfPreferred").value = person?.preferredName || "";
    document.getElementById("pfDob").value = person?.dateOfBirth || "";
    document.getElementById("pfEmails").value =
        (person?.emails || []).map(e => e.email).join("\n");
    document.getElementById("pfPhones").value =
        (person?.phones || []).map(ph => ph.number + (ph.type ? " " + ph.type : "")).join("\n");
    document.getElementById("pfNotes").value = person?.notes || "";
    // preferences need a persisted person id: shown only when editing (CR-005)
    const prefsWrap = document.getElementById("pfPrefsWrap");
    if (prefsWrap) {
        prefsWrap.hidden = !person;
        if (person) renderPreferences("pfPrefs", "people", person.id);
        else document.getElementById("pfPrefs").innerHTML = "";
    }
    // self-serve link status (CR-006): also editing-only
    const linkWrap = document.getElementById("pfLinkWrap");
    if (linkWrap) {
        linkWrap.hidden = !person;
        if (person) renderPersonLink(person.id);
    }
    openDialog("personForm");
}

// the linked/not-linked indicator + Unlink in person detail (CR-006). Unlink
// leaves the Keycloak account alone — the account then sees "no membership
// linked"; re-provisioning re-adopts it.
async function renderPersonLink(personId) {
    const statusEl = document.getElementById("pfLinkStatus");
    const unlink = document.getElementById("pfUnlink");
    statusEl.textContent = "…";
    unlink.hidden = true;
    const response = await registerCall(`/admin/people/${personId}/keycloak-link`);
    if (!response) return;
    const link = await response.json();
    statusEl.textContent = link.linked
        ? `Linked to Keycloak account ${link.subject} — this person can use the member webapp.`
        : "Not linked — no self-serve account (run provisioning on the Users page).";
    unlink.hidden = !link.linked;
    unlink.onclick = async () => {
        if (!confirm("Unlink this person's self-serve account? The Keycloak account "
                + "remains but will no longer see a membership.")) return;
        if (await registerCall(`/admin/people/${personId}/keycloak-link`, {method: "DELETE"})) {
            say(`Unlinked person #${personId}.`);
            renderPersonLink(personId);
        }
    };
}

function personPayload() {
    const lines = (id) => document.getElementById(id).value
        .split("\n").map(s => s.trim()).filter(Boolean);
    const value = (id) => document.getElementById(id).value.trim() || null;
    return {
        title: value("pfTitle"),
        givenName: value("pfGiven"),
        familyName: value("pfFamily"),
        preferredName: value("pfPreferred"),
        dateOfBirth: value("pfDob"),
        notes: value("pfNotes"),
        emails: lines("pfEmails").map((email, i) => ({email, isPrimary: i === 0})),
        phones: lines("pfPhones").map((line, i) => {
            const [number, type] = [line.replace(/\s+(MOBILE|HOME|WORK)$/i, "").trim(),
                (line.match(/\s+(MOBILE|HOME|WORK)$/i) || [])[1]];
            return {number, type: type ? type.toUpperCase() : null, isPrimary: i === 0};
        }),
    };
}

async function savePerson() {
    const path = editingPersonId ? `/admin/people/${editingPersonId}` : "/admin/people";
    const response = await registerCall(path, {
        method: editingPersonId ? "PUT" : "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify(personPayload()),
    });
    if (!response) return;
    const person = await response.json();
    say(`Saved person #${person.id} (${person.givenName} ${person.familyName}).`);
    closeDialog("personForm");
    renderPeople();
}

// ---- register: households ---------------------------------------------------

let openHouseholdId = null;

// Person type-ahead. Replaces the raw person-id number inputs: type a name or
// email, hit GET /api/admin/people?q=, pick a match. The chosen id rides on the
// input's dataset; editing the text clears it so a stale pick can't be
// submitted. Each picker needs an <input id> and a sibling <ul id="{id}Results">.
function wirePersonPicker(inputId) {
    const input = document.getElementById(inputId);
    const list = document.getElementById(inputId + "Results");
    let timer = null;
    const clear = () => { list.innerHTML = ""; list.hidden = true; };
    input.oninput = () => {
        delete input.dataset.personId; // text changed → any prior pick is stale
        const q = input.value.trim();
        clearTimeout(timer);
        if (q.length < 2) { clear(); return; }
        timer = setTimeout(async () => {
            const response = await registerCall(`/admin/people?${new URLSearchParams({q, limit: "8"})}`);
            if (!response) return;
            const people = (await response.json()).people;
            list.innerHTML = "";
            for (const p of people) {
                const name = personName(p);
                const email = p.emails.find(e => e.isPrimary) || p.emails[0];
                const li = document.createElement("li");
                li.textContent = `${name} (#${p.id})` + (email ? ` · ${email.email}` : "");
                li.onclick = () => {
                    input.value = name;
                    input.dataset.personId = p.id;
                    clear();
                };
                list.appendChild(li);
            }
            list.hidden = people.length === 0;
        }, 200);
    };
    // a click on a result fires before blur closes the list; the delay lets it
    input.onblur = () => setTimeout(clear, 150);
}
function pickedPersonId(inputId) {
    const v = document.getElementById(inputId).dataset.personId;
    return v ? Number(v) : null;
}
function resetPicker(inputId) {
    const input = document.getElementById(inputId);
    input.value = "";
    delete input.dataset.personId;
    const list = document.getElementById(inputId + "Results");
    list.innerHTML = "";
    list.hidden = true;
}
function personName(p) {
    return [p.title, p.givenName, p.familyName].filter(Boolean).join(" ")
        + (p.preferredName ? ` (${p.preferredName})` : "");
}

async function renderHouseholds() {
    const q = document.getElementById("householdSearch").value.trim();
    const params = new URLSearchParams({limit: "50"});
    if (q) params.set("q", q);
    const response = await registerCall(`/admin/households?${params}`);
    if (!response) return;
    const page = await response.json();
    const body = document.querySelector("#households tbody");
    body.innerHTML = "";
    for (const h of page.households) {
        const row = body.insertRow();
        row.insertCell().textContent = h.id;
        row.insertCell().textContent = h.householdName || "—";
        row.insertCell().textContent = `${h.primaryContactName} (#${h.primaryContactPersonId})`;
        row.insertCell().textContent = h.status;
        row.insertCell().textContent = h.currentMembers;
        const open = document.createElement("button");
        open.textContent = "Members";
        open.onclick = () => openHousehold(h.id);
        row.insertCell().appendChild(open);
    }
    document.getElementById("householdsTotal").textContent = `${page.total} match(es)`;
}

async function openHousehold(id) {
    const response = await registerCall(`/admin/households/${id}`);
    if (!response) return;
    const household = await response.json();
    openHouseholdId = id;
    document.getElementById("householdDetailTitle").textContent =
        `Household #${id}: ${household.householdName || "(unnamed)"}`;
    const body = document.querySelector("#householdMembers tbody");
    body.innerHTML = "";
    for (const m of household.members) {
        const row = body.insertRow();
        const primary = m.personId === household.primaryContactPersonId && !m.leftDate;
        row.insertCell().textContent =
            `${m.givenName} ${m.familyName} (#${m.personId})` + (primary ? " — primary" : "");
        row.insertCell().textContent = m.relationshipType;
        row.insertCell().textContent = m.joinedDate;
        row.insertCell().textContent = m.leftDate || "";
        const cell = row.insertCell();
        if (!m.leftDate && !primary) {
            const remove = document.createElement("button");
            remove.textContent = "Remove";
            remove.onclick = async () => {
                if (await registerCall(`/admin/households/${id}/people/${m.personId}`,
                        {method: "DELETE"})) {
                    say(`Recorded ${m.givenName} ${m.familyName} leaving the household.`);
                    openHousehold(id);
                    renderHouseholds();
                }
            };
            cell.appendChild(remove);
        }
    }
    fillPeriodTypeSelects(); // the household's "New membership" period/type pickers
    resetPicker("hdPersonId");
    renderPreferences("hdPrefs", "households", id); // CR-005 household-level defaults
    openDialog("householdDetail");
}

async function saveHousehold() {
    const name = document.getElementById("hfName").value.trim() || null;
    const contact = pickedPersonId("hfContact");
    if (!contact) return say("Choose a primary contact — search by name or email.", true);
    const response = await registerCall("/admin/households", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({householdName: name, primaryContactPersonId: contact}),
    });
    if (!response) return;
    const household = await response.json();
    say(`Created household #${household.id}.`);
    closeDialog("householdForm");
    renderHouseholds();
}

async function addHouseholdMember() {
    const personId = pickedPersonId("hdPersonId");
    if (!personId || openHouseholdId === null) return say("Choose a person — search by name or email.", true);
    const relationship = document.getElementById("hdRelationship").value;
    if (await registerCall(`/admin/households/${openHouseholdId}/people`, {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({personId, relationshipType: relationship}),
    })) {
        say(`Added person #${personId}.`);
        resetPicker("hdPersonId");
        openHousehold(openHouseholdId);
        renderHouseholds();
    }
}

// ---- register: CSV import ---------------------------------------------------

// the CSV that last previewed clean; Apply is gated on this being non-null so
// you can never apply a file the server hasn't just validated error-free
let importCsv = null;

function importPath(base) {
    const period = document.getElementById("importPeriod").value.trim();
    return period ? `${base}?period=${encodeURIComponent(period)}` : base;
}

// the target-period dropdown, filled from the loaded periods (blank = the
// period covering today, which is what the import resolves with no ?period)
function fillImportPeriods() {
    const select = document.getElementById("importPeriod");
    if (!select) return;
    const chosen = select.value;
    select.innerHTML = "";
    const blank = document.createElement("option");
    blank.value = "";
    blank.textContent = "(current period)";
    select.appendChild(blank);
    for (const p of periodsCache) {
        const o = document.createElement("option");
        o.value = p.name;         // the import API resolves ?period by name
        o.textContent = p.name;
        o.selected = p.name === chosen;
        select.appendChild(o);
    }
    // no periods → memberships in the file have nowhere to land; guide the user.
    // (people/households still import; only rows with a membershipType need a period)
    const hint = document.getElementById("importPeriodHint");
    if (hint) {
        const none = periodsCache.length === 0;
        hint.textContent = none
            ? "No membership periods exist yet. Create one under Renewals → New period "
              + "before importing rows that carry a membershipType."
            : "";
        hint.hidden = !none;
    }
}

// posts the CSV and returns {status, report}; the report is read even on the
// 400 that apply gives when validation fails, so its errors can be shown
async function importCall(base, csv) {
    const response = await Auth.api(importPath(base), {
        method: "POST",
        headers: {"Content-Type": "text/csv"},
        body: csv,
    });
    if (!response) return null;
    if (response.status === 413) { say("CSV exceeds the 1 MB limit.", true); return null; }
    // preview and the apply-with-errors 400 both return the report as JSON; an
    // unexpected non-JSON body (a 500 page) is surfaced rather than swallowed
    const text = await response.text();
    try {
        return {status: response.status, report: JSON.parse(text)};
    } catch {
        say(`Import failed (HTTP ${response.status}): ${text.slice(0, 300)}`, true);
        return null;
    }
}

async function previewImport() {
    const input = document.getElementById("importFile");
    if (!input.files || !input.files[0]) return say("Choose a CSV file first.", true);
    const csv = await input.files[0].text();
    const result = await importCall("/admin/import/preview", csv);
    if (!result) return;
    renderImportReport(result.report);
    const clean = result.report.errors.length === 0;
    importCsv = clean ? csv : null;
    document.getElementById("importApply").disabled = !clean;
    say(clean
        ? `Preview OK — would create ${describeCounts(result.report.toCreate)}. Review below, then Apply.`
        : `Preview found ${result.report.errors.length} error(s) — fix the file and preview again.`, !clean);
}

async function applyImport() {
    if (importCsv === null) return say("Preview a clean file before applying.", true);
    const result = await importCall("/admin/import", importCsv);
    if (!result) return;
    renderImportReport(result.report);
    importCsv = null;
    document.getElementById("importApply").disabled = true;
    if (result.status === 200) {
        say(`Imported ${describeCounts(result.report.created)}.`);
        renderPeople();
        renderHouseholds();
    } else {
        say("Apply refused — the file no longer validates (see the report).", true);
    }
}

function describeCounts(c) {
    return `${c.people} people, ${c.households} households, ${c.memberships} memberships, ${c.payments} payments`;
}

function renderImportReport(report) {
    const el = document.getElementById("importReport");
    el.innerHTML = "";
    const line = (html) => { const p = document.createElement("p"); p.innerHTML = html; el.appendChild(p); };
    line(`${report.rows} data row(s).`);
    line(`<strong>To create:</strong> ${describeCounts(report.toCreate)}`);
    if (report.created) line(`<strong>Created:</strong> ${describeCounts(report.created)}`);
    importIssueTable("Errors", report.errors, "message", el);
    importIssueTable("Warnings", report.warnings, "message", el);
    importIssueTable("Skipped", report.skipped, "reason", el);
}

function importIssueTable(title, rows, textKey, parent) {
    if (!rows.length) return;
    const heading = document.createElement("h4");
    heading.textContent = `${title} (${rows.length})`;
    parent.appendChild(heading);
    const table = document.createElement("table");
    const head = table.createTHead().insertRow();
    for (const label of ["Line", title.replace(/s$/, "")]) {
        const th = document.createElement("th");
        th.textContent = label;
        head.appendChild(th);
    }
    const tbody = table.createTBody();
    for (const r of rows) {
        const tr = tbody.insertRow();
        tr.insertCell().textContent = r.line;
        tr.insertCell().textContent = r[textKey];
    }
    parent.appendChild(table);
}

// ---- renewals: periods, memberships, payments (CR-003) ----------------------

let periodsCache = [];        // last-loaded periods (also feeds household "New membership")
let openMembershipId = null;

const STATUS_LABELS = {
    PENDING_PAYMENT: "Unpaid", ACTIVE: "Paid", LAPSED: "Lapsed",
    APPLIED: "Applied", CEASED: "Ceased",
};
const statusLabel = (s) => STATUS_LABELS[s] || s;
// Paid green, Unpaid amber, Applied blue, Lapsed/Ceased grey (CR-009)
const STATUS_BADGE = {
    ACTIVE: "green", PENDING_PAYMENT: "amber", APPLIED: "blue",
    LAPSED: "grey", CEASED: "grey",
};
function statusBadge(status) {
    const span = document.createElement("span");
    span.className = `badge badge-${STATUS_BADGE[status] || "grey"}`;
    span.textContent = statusLabel(status);
    return span;
}
const dollars = (cents) => "$" + (cents / 100).toFixed(2);
const today = () => new Date().toISOString().slice(0, 10);
// an ISO date wound forward one year (string-based, so no timezone surprises);
// "" for a null/absent date
function plusYear(iso) {
    if (!iso) return "";
    const [y, m, d] = iso.split("-");
    return `${Number(y) + 1}-${m}-${d}`;
}
function toCents(value) {
    const n = Math.round(parseFloat(value) * 100);
    return Number.isFinite(n) ? n : 0;
}

function selectedPeriodId() {
    const v = document.getElementById("periodSelect").value;
    return v ? Number(v) : null;
}
function selectedPeriod() {
    return periodsCache.find(p => p.id === selectedPeriodId()) || null;
}
// the membership types, unioned from every period's prices (there is no type API)
function allTypes() {
    const seen = new Map();
    for (const p of periodsCache) for (const pr of p.prices) seen.set(pr.type, pr.typeId);
    return [...seen.entries()].map(([type, typeId]) => ({type, typeId}));
}

async function loadPeriods(selectId) {
    const response = await registerCall("/admin/periods");
    if (!response) return;
    periodsCache = (await response.json()).periods;
    fillImportPeriods(); // the CSV-import "Target period" picker shares the same list
    const select = document.getElementById("periodSelect");
    if (!select) return; // import page only needs the dropdown above — no renewals UI here
    const keep = selectId ?? (selectedPeriodId() || (periodsCache[0] && periodsCache[0].id));
    select.innerHTML = "";
    for (const p of periodsCache) {
        const o = document.createElement("option");
        o.value = p.id;
        o.textContent = p.name;
        o.selected = p.id === keep;
        select.appendChild(o);
    }
    renderPeriodSummary();
    await renderMemberships();
}

function renderPeriodSummary() {
    const p = selectedPeriod();
    const el = document.getElementById("periodSummary");
    if (!p) { el.textContent = "No period. Create one to begin."; return; }
    const prices = p.prices.map(pr => `${pr.type} ${dollars(pr.amountCents)}`).join(", ");
    el.textContent = `${p.name}: ${p.startDate} → ${p.endDate}. Prices: ${prices || "—"}.`
        + (p.journalPriceCents != null ? ` Journal add-on ${dollars(p.journalPriceCents)}.` : "");
    document.getElementById("journalPrice").value =
        p.journalPriceCents != null ? (p.journalPriceCents / 100).toFixed(2) : "";
}

async function saveJournalPrice() {
    const id = selectedPeriodId();
    if (!id) return;
    const raw = document.getElementById("journalPrice").value.trim();
    const response = await registerCall(`/admin/periods/${id}`, {
        method: "PUT", headers: {"Content-Type": "application/json"},
        body: JSON.stringify({journalPriceCents: raw === "" ? null : toCents(raw)}),
    });
    if (!response) return;
    say(raw === "" ? "Journal add-on no longer offered this period."
        : `Journal add-on price saved (${dollars(toCents(raw))}).`);
    await loadPeriods(id);
}

async function renderMemberships() {
    const periodId = selectedPeriodId();
    if (!periodId) return;
    const q = document.getElementById("memberSearch").value.trim();
    const status = document.getElementById("statusFilter").value;
    const params = new URLSearchParams({limit: "200"});
    if (q) params.set("q", q);
    if (status) params.set("status", status);
    const response = await registerCall(`/admin/periods/${periodId}/memberships?${params}`);
    if (!response) return;
    const page = await response.json();
    const tbody = document.querySelector("#memberships tbody");
    tbody.innerHTML = "";
    for (const r of page.rows) {
        const row = tbody.insertRow();
        row.insertCell().textContent = r.householdName || `#${r.householdId}`;
        row.insertCell().textContent = r.primaryContactName;
        row.insertCell().textContent = r.memberNames.join(", ");
        row.insertCell().textContent = r.typeName;
        row.insertCell().appendChild(statusBadge(r.status));
        row.insertCell().textContent = dollars(r.amountDueCents);
        row.insertCell().textContent = dollars(r.amountPaidCents);
        const manage = document.createElement("button");
        manage.textContent = "Manage";
        manage.onclick = () => openMembership(r.membershipId);
        row.insertCell().appendChild(manage);
    }
    const s = page.summary;
    const counts = Object.entries(s.countsByStatus)
        .map(([k, v]) => `${statusLabel(k)} ${v}`).join(", ");
    document.getElementById("membershipsTotal").textContent =
        `${page.total} shown. Period totals — ${counts || "none"}; `
        + `collected ${dollars(s.totalCollectedCents)} of ${dollars(s.totalDueCents)} due.`;
}

async function openMembership(id) {
    const response = await registerCall(`/admin/memberships/${id}`);
    if (!response) return;
    const m = await response.json();
    openMembershipId = id;
    document.getElementById("mdTitle").textContent =
        `Membership #${id}: ${m.householdName || "household #" + m.householdId} (${m.periodName})`;
    const members = m.people.map(p =>
        `${p.givenName} ${p.familyName}${p.voting ? "" : " (non-voting)"}`).join(", ");
    document.getElementById("mdSummary").textContent =
        `${m.typeName} — ${statusLabel(m.status)}. Due ${dollars(m.amountDueCents)}, `
        + `paid ${dollars(m.amountPaidCents)}.` + (members ? " Members: " + members : "");
    renderMembershipActions(m);
    renderMembershipPayments(m);
    // prep the payment form for this membership (prefilled with the balance)
    closeDialog("paymentForm");
    document.getElementById("payDate").value = today();
    document.getElementById("payMembership").value =
        ((m.amountDueCents - m.amountPaidCents) / 100).toFixed(2);
    document.getElementById("payExtra").innerHTML = "";
    document.getElementById("payRef").value = "";
    document.getElementById("payNotes").value = "";
    openDialog("membershipDetail");
}

function renderMembershipActions(m) {
    const el = document.getElementById("mdActions");
    el.innerHTML = "";
    const btn = (label, handler) => {
        const b = document.createElement("button");
        b.textContent = label;
        b.style.marginRight = ".4rem";
        b.onclick = handler;
        el.appendChild(b);
    };
    if (m.status === "PENDING_PAYMENT") btn("Lapse", () => transition(m.id, {status: "LAPSED"}));
    if (m.status === "LAPSED") btn("Undo lapse", () => transition(m.id, {status: "PENDING_PAYMENT"}));
    if (m.status !== "CEASED") btn("Cease…", () => ceaseMembership(m.id));
    if (m.status !== "CEASED") btn("Copy pay link", () => copyPayLink(m.id));
}

// mint a magic pay link (CR-004) for pasting into a manual email; each click
// mints a fresh token — older unexpired links keep working
async function copyPayLink(id) {
    const response = await registerCall(`/admin/memberships/${id}/pay-link`, {method: "POST"});
    if (!response) return;
    const link = await response.json();
    try {
        await navigator.clipboard.writeText(link.url);
        say(`Pay link copied to clipboard (valid until ${link.expiresAt.slice(0, 10)}): ${link.url}`);
    } catch (e) {
        // clipboard needs a secure context (absent on http://LAN-IP) — show it instead
        say(`Pay link (copy manually, valid until ${link.expiresAt.slice(0, 10)}): ${link.url}`);
    }
}

async function transition(id, body) {
    const response = await registerCall(`/admin/memberships/${id}`, {
        method: "PUT", headers: {"Content-Type": "application/json"}, body: JSON.stringify(body),
    });
    if (!response) return;
    say(`Membership #${id} updated.`);
    openMembership(id);
    renderMemberships();
}

function ceaseMembership(id) {
    const reason = prompt("Cessation reason — RESIGNED / DECEASED / OTHER:", "RESIGNED");
    if (!reason) return;
    const date = prompt("Ceased date (YYYY-MM-DD):", today());
    if (!date) return;
    transition(id, {status: "CEASED", ceasedDate: date, cessationReason: reason.trim().toUpperCase()});
}

function renderMembershipPayments(m) {
    const tbody = document.querySelector("#mdPayments tbody");
    tbody.innerHTML = "";
    for (const p of m.payments) {
        const row = tbody.insertRow();
        row.insertCell().textContent = p.receivedDate;
        row.insertCell().textContent = dollars(p.amountCents);
        row.insertCell().textContent = p.method;
        row.insertCell().textContent =
            p.allocations.map(a => `${a.type} ${dollars(a.amountCents)}`).join(", ");
        row.insertCell().textContent = p.recordedBy;
        const reverse = document.createElement("button");
        reverse.textContent = "Reverse";
        reverse.onclick = () => reversePayment(m.id, p);
        row.insertCell().appendChild(reverse);
    }
}

async function reversePayment(membershipId, p) {
    if (!confirm(`Reverse payment #${p.id} (${dollars(p.amountCents)})? `
            + "This records an equal-and-opposite payment.")) return;
    const body = {
        receivedDate: today(),
        // reversing a webhook STRIPE payment records negative STRIPE — the
        // sanctioned refund-recording shape (CR-004), keeping per-method
        // totals reconcilable against the Stripe dashboard
        amountCents: -p.amountCents,
        method: p.method,
        notes: `reversal of payment #${p.id}`,
        allocations: p.allocations.map(a =>
            ({type: a.type, membershipId: a.membershipId, amountCents: -a.amountCents})),
    };
    const response = await registerCall("/admin/payments", {
        method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(body),
    });
    if (!response) return;
    say(`Reversed payment #${p.id}.`);
    openMembership(membershipId);
    renderMemberships();
}

function addAllocationLine() {
    const div = document.createElement("div");
    const type = document.createElement("select");
    for (const t of ["DONATION", "JOURNAL", "OTHER"]) {
        const o = document.createElement("option");
        o.value = o.textContent = t;
        type.appendChild(o);
    }
    const amount = document.createElement("input");
    amount.type = "number";
    amount.step = "0.01";
    amount.placeholder = "amount $";
    amount.style.maxWidth = "8rem";
    const remove = document.createElement("button");
    remove.type = "button";
    remove.textContent = "×";
    remove.onclick = () => div.remove();
    div.className = "allocLine";
    div.append(type, " ", amount, " ", remove);
    document.getElementById("payExtra").appendChild(div);
}

async function submitPayment() {
    if (openMembershipId === null) return;
    const allocations = [];
    const memCents = toCents(document.getElementById("payMembership").value);
    if (memCents !== 0) {
        allocations.push({type: "MEMBERSHIP", membershipId: openMembershipId, amountCents: memCents});
    }
    for (const line of document.querySelectorAll("#payExtra .allocLine")) {
        const type = line.querySelector("select").value;
        const cents = toCents(line.querySelector("input").value);
        if (cents === 0) continue;
        // JOURNAL rides the membership renewal; a standalone donation names nobody
        allocations.push({type, membershipId: type === "JOURNAL" ? openMembershipId : null, amountCents: cents});
    }
    if (!allocations.length) return say("Enter at least one non-zero amount.", true);
    const amountCents = allocations.reduce((sum, a) => sum + a.amountCents, 0);
    const response = await registerCall("/admin/payments", {
        method: "POST", headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
            receivedDate: document.getElementById("payDate").value,
            amountCents,
            method: document.getElementById("payMethod").value,
            bankReference: document.getElementById("payRef").value.trim() || null,
            notes: document.getElementById("payNotes").value.trim() || null,
            allocations,
        }),
    });
    if (!response) return;
    const result = await response.json();
    say(`Recorded payment #${result.id}.`
        + (result.warnings && result.warnings.length ? " " + result.warnings.join("; ") : ""));
    closeDialog("paymentForm");
    openMembership(openMembershipId);
    renderMemberships();
}

// ---- renewals: new period, rollover, bulk lapse, CSV export -----------------

function openPeriodForm() {
    // pre-fill from the selected period — the common case is "next year, same
    // prices": dates wound forward a year, prices carried over, name left blank
    const base = selectedPeriod();
    document.getElementById("npName").value = "";
    document.getElementById("npStart").value = base ? plusYear(base.startDate) : "";
    document.getElementById("npEnd").value = base ? plusYear(base.endDate) : "";
    document.getElementById("npRenewalOpen").value = base ? plusYear(base.renewalOpenDate) : "";
    document.getElementById("npCutoff").value = base ? plusYear(base.lateJoiningCutoff) : "";
    document.getElementById("npJournal").value =
        base && base.journalPriceCents != null ? (base.journalPriceCents / 100).toFixed(2) : "";
    const priceByType = new Map((base ? base.prices : []).map(pr => [pr.type, pr.amountCents]));
    const container = document.getElementById("npPrices");
    container.innerHTML = "";
    const types = allTypes();
    if (!types.length) {
        container.textContent = "No membership types found yet — none of the existing periods carry a price.";
    }
    for (const t of types) {
        const label = document.createElement("label");
        label.textContent = `Price for ${t.type} ($) `;
        const input = document.createElement("input");
        input.type = "number";
        input.step = "0.01";
        input.dataset.type = t.type;
        if (priceByType.has(t.type)) input.value = (priceByType.get(t.type) / 100).toFixed(2);
        label.appendChild(input);
        container.appendChild(label);
    }
    openDialog("periodForm");
}

async function savePeriod() {
    const prices = [];
    for (const input of document.querySelectorAll("#npPrices input")) {
        if (input.value.trim() === "") return say(`Enter a price for ${input.dataset.type}.`, true);
        prices.push({type: input.dataset.type, amountCents: toCents(input.value)});
    }
    const name = document.getElementById("npName").value.trim();
    const startDate = document.getElementById("npStart").value;
    const endDate = document.getElementById("npEnd").value;
    if (!name || !startDate || !endDate) return say("Name, start and end dates are required.", true);
    const response = await registerCall("/admin/periods", {
        method: "POST", headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
            name, startDate, endDate,
            renewalOpenDate: document.getElementById("npRenewalOpen").value || null,
            lateJoiningCutoff: document.getElementById("npCutoff").value || null,
            journalPriceCents: document.getElementById("npJournal").value.trim() === ""
                ? null : toCents(document.getElementById("npJournal").value),
            prices,
        }),
    });
    if (!response) return;
    const p = await response.json();
    say(`Created period ${p.name}.`);
    closeDialog("periodForm");
    await loadPeriods(p.id);
}

async function rolloverPreview() {
    const id = selectedPeriodId();
    if (!id) return;
    const response = await registerCall(`/admin/periods/${id}/rollover/preview`, {method: "POST"});
    if (!response) return;
    const report = await response.json();
    renderRolloverReport(report, false);
    document.getElementById("rolloverApply").disabled = report.errors.length > 0 || report.toCreate === 0;
}

async function rolloverApply() {
    const id = selectedPeriodId();
    if (!id) return;
    if (!confirm("Apply rollover? This creates memberships for the prior year's paid households.")) return;
    const response = await registerCall(`/admin/periods/${id}/rollover`, {method: "POST"});
    if (!response) return;
    renderRolloverReport(await response.json(), true);
    document.getElementById("rolloverApply").disabled = true;
    await loadPeriods(id);
}

function renderRolloverReport(report, applied) {
    const el = document.getElementById("rolloverReport");
    el.innerHTML = "";
    const line = (html) => { const p = document.createElement("p"); p.innerHTML = html; el.appendChild(p); };
    line(`Source: <strong>${report.fromPeriodName || "(none found)"}</strong> → target `
        + `<strong>${report.targetPeriodName}</strong>.`);
    line(applied
        ? `<strong>Created ${report.created}</strong> memberships.`
        : `<strong>Would create ${report.toCreate}</strong> memberships.`);
    if (report.skipped.length) {
        line(`Skipped ${report.skipped.length}: `
            + report.skipped.map(s => `#${s.householdId} (${s.reason})`).join("; "));
    }
    if (report.errors.length) line(`<span style="color:#a00">Errors: ${report.errors.join("; ")}</span>`);
}

async function lapseAll() {
    const id = selectedPeriodId();
    if (!id) return;
    if (!confirm("Lapse ALL unpaid (Unpaid/PENDING_PAYMENT) memberships in this period?")) return;
    const response = await registerCall(`/admin/periods/${id}/lapse-unpaid`, {method: "POST"});
    if (!response) return;
    const r = await response.json();
    say(`Lapsed ${r.lapsed} unpaid membership(s).`);
    renderMemberships();
}

// bearer auth means no cookie ride-along: fetch with the header, then save the Blob
async function exportCsv(kind) {
    const id = selectedPeriodId();
    if (!id) return;
    const response = await Auth.api(`/admin/periods/${id}/export/${kind}`);
    if (!response) return;
    if (!response.ok) return say(`Export failed (HTTP ${response.status}).`, true);
    const url = URL.createObjectURL(await response.blob());
    const a = document.createElement("a");
    a.href = url;
    a.download = kind;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
}

// household detail "New membership": fill the period/type selects from the cache
function fillPeriodTypeSelects() {
    const periodSel = document.getElementById("hmPeriod");
    periodSel.innerHTML = "";
    for (const p of periodsCache) {
        const o = document.createElement("option");
        o.value = p.id;
        o.textContent = p.name;
        periodSel.appendChild(o);
    }
    const typeSel = document.getElementById("hmType");
    typeSel.innerHTML = "";
    for (const t of allTypes()) {
        const o = document.createElement("option");
        o.value = t.typeId;
        o.textContent = t.type;
        typeSel.appendChild(o);
    }
}

async function createHouseholdMembership() {
    if (openHouseholdId === null) return say("Open a household first.", true);
    const periodId = Number(document.getElementById("hmPeriod").value);
    const typeId = Number(document.getElementById("hmType").value);
    if (!periodId || !typeId) return say("Pick a period and a type.", true);
    const response = await registerCall("/admin/memberships", {
        method: "POST", headers: {"Content-Type": "application/json"},
        body: JSON.stringify({householdId: openHouseholdId, membershipPeriodId: periodId, membershipTypeId: typeId}),
    });
    if (!response) return;
    const m = await response.json();
    say(`Created membership #${m.id} for the household.` + (m.warning ? " " + m.warning : ""));
    renderMemberships();
}

function wireRenewals() {
    const on = (id, handler) => { document.getElementById(id).onclick = handler; };
    document.getElementById("periodSelect").onchange = () => {
        renderPeriodSummary();
        renderMemberships();
        document.getElementById("rolloverApply").disabled = true;
        document.getElementById("rolloverReport").innerHTML = "";
    };
    on("periodNew", openPeriodForm);
    on("journalPriceSave", saveJournalPrice);
    on("npSave", savePeriod);
    on("npCancel", () => closeDialog("periodForm"));
    on("rolloverPreview", rolloverPreview);
    on("rolloverApply", rolloverApply);
    on("memberSearchGo", renderMemberships);
    document.getElementById("memberSearch").onkeydown = (e) => { if (e.key === "Enter") renderMemberships(); };
    document.getElementById("statusFilter").onchange = renderMemberships;
    on("lapseAll", lapseAll);
    on("exportAgm", () => exportCsv("agm-register.csv"));
    on("exportLabels", () => exportCsv("mailing-labels.csv"));
    on("exportFinancial", () => exportCsv("financial.csv"));
    on("mdRecordPayment", () => openDialog("paymentForm"));
    on("payAddLine", addAllocationLine);
    on("paySave", submitPayment);
    on("payCancel", () => closeDialog("paymentForm"));
    on("mdClose", () => closeDialog("membershipDetail"));
    on("hmCreate", createHouseholdMembership);
    document.getElementById("renewalsSection").hidden = false;
}

// the CSV import lives on its own page (import.html) — a one-off bulk load
function wireImport() {
    document.getElementById("importPreview").onclick = previewImport;
    document.getElementById("importApply").onclick = applyImport;
    document.getElementById("importSection").hidden = false;
}

function wireRegister() {
    const on = (id, handler) => { document.getElementById(id).onclick = handler; };
    const enter = (id, handler) =>
        { document.getElementById(id).onkeydown = (e) => { if (e.key === "Enter") handler(); }; };
    on("personSearchGo", renderPeople);      enter("personSearch", renderPeople);
    on("personNew", () => openPersonForm(null));
    on("personSave", savePerson);
    on("personCancel", () => closeDialog("personForm"));
    on("householdSearchGo", renderHouseholds); enter("householdSearch", renderHouseholds);
    on("householdNew", () => {
        document.getElementById("hfName").value = "";
        resetPicker("hfContact");
        openDialog("householdForm");
    });
    on("householdSave", saveHousehold);
    on("householdCancel", () => closeDialog("householdForm"));
    on("hdAdd", addHouseholdMember);
    on("hdClose", () => closeDialog("householdDetail"));
    wirePersonPicker("hfContact");   // household primary-contact search
    wirePersonPicker("hdPersonId");  // household add-member search
    document.getElementById("registerSection").hidden = false;
}

// ---- new member (CR-010) -----------------------------------------------------

// the second person's payload once the dialog is saved; null = not added.
// Skippable per the CR-010 design — a HOUSEHOLD-shaped membership entered
// with one person is real life (the partner's details aren't always to hand).
let nmSecondPerson = null;

// the period's price list carries minimum/maximumPeople (CR-010) so this page
// can mirror the server's people-count rule without a type-management API
function nmSelectedType() {
    const period = periodsCache.find(p => p.id === Number(document.getElementById("nmPeriod").value));
    const typeId = Number(document.getElementById("nmType").value);
    return period ? period.prices.find(pr => pr.typeId === typeId) : null;
}

function nmFillPeriods() {
    const select = document.getElementById("nmPeriod");
    const todayStr = today();
    // default: the period covering today, else the newest (periodsCache is
    // newest-first, per PeriodStore.list())
    const covering = periodsCache.find(p => p.startDate <= todayStr && todayStr <= p.endDate);
    const defaultId = covering ? covering.id : (periodsCache[0] && periodsCache[0].id);
    select.innerHTML = "";
    for (const p of periodsCache) {
        const o = document.createElement("option");
        o.value = p.id;
        o.textContent = p.name;
        o.selected = p.id === defaultId;
        select.appendChild(o);
    }
    nmFillTypes();
}

// populates the type select only — does NOT pop the second-person dialog.
// Called from initial load and from a period change; popping the dialog here
// would steal modal focus before the admin has even typed the person's name
// (HOUSEHOLD sorts first, so it is the page's default type on every load).
function nmFillTypes() {
    const period = periodsCache.find(p => p.id === Number(document.getElementById("nmPeriod").value));
    const select = document.getElementById("nmType");
    select.innerHTML = "";
    for (const pr of (period ? period.prices : [])) {
        const o = document.createElement("option");
        o.value = pr.typeId;
        o.textContent = `${pr.type} — ${dollars(pr.amountCents)}`;
        select.appendChild(o);
    }
    nmSyncPriceAndStatus();
}

function nmSyncPriceAndStatus() {
    const type = nmSelectedType();
    document.getElementById("nmPriceDisplay").textContent = type ? `Price: ${dollars(type.amountCents)}` : "";
    nmRenderStatus();
}

// bound to #nmType's change event only (a real user action) — the one place
// the second-person dialog is allowed to pop itself open
function nmOnTypeChanged() {
    const type = nmSelectedType();
    const needsSecond = type && type.minimumPeople != null && type.minimumPeople >= 2;
    if (needsSecond && !nmSecondPerson) {
        openSecondPersonForm();
    } else if (!needsSecond) {
        // a stale auto-opened dialog from a prior HOUSEHOLD-shaped selection
        // would otherwise block Create after switching to e.g. SINGLE
        closeDialog("secondPersonForm");
    }
    nmSyncPriceAndStatus();
}

function nmRenderStatus() {
    const type = nmSelectedType();
    const warn = document.getElementById("nmMinWarning");
    const conflict = document.getElementById("nmConflictWarning");
    const create = document.getElementById("nmCreate");
    warn.hidden = true;
    conflict.hidden = true;
    create.disabled = false;
    if (type) {
        // maximumPeople caps formal (MEMBER) members only — a PARTNER/DEPENDANT/
        // OTHER second person doesn't count against it (mirrors AdminNewMemberResource)
        const count = nmSecondPerson ? 2 : 1;
        const memberCount = 1 + (nmSecondPerson && nmSecondPerson.relationship === "MEMBER" ? 1 : 0);
        if (type.maximumPeople != null && memberCount > type.maximumPeople) {
            conflict.textContent = `${type.type} allows at most ${type.maximumPeople} `
                + `formal ${type.maximumPeople === 1 ? "member" : "members"} — change the second `
                + "person's relationship, remove them, or choose a different type.";
            conflict.hidden = false;
            create.disabled = true;
        } else if (type.minimumPeople != null && count < type.minimumPeople) {
            warn.textContent = `${type.type} normally has at least ${type.minimumPeople} people `
                + "— add the second person now, or later via household detail (they won't be "
                + "retroactively added to this membership).";
            warn.hidden = false;
        }
    }
    const summary = document.getElementById("nmSecondSummary");
    summary.innerHTML = "";
    if (nmSecondPerson) {
        summary.append(`Second person: ${nmSecondPerson.givenName} ${nmSecondPerson.familyName} `
            + `(${nmSecondPerson.relationship}). `);
        const edit = document.createElement("button");
        edit.type = "button"; edit.className = "secondary"; edit.textContent = "Edit";
        edit.onclick = () => openSecondPersonForm();
        const remove = document.createElement("button");
        remove.type = "button"; remove.className = "secondary"; remove.textContent = "Remove";
        remove.onclick = () => { nmSecondPerson = null; nmRenderStatus(); };
        summary.append(edit, " ", remove);
    } else {
        summary.textContent = "No second person added yet.";
    }
}

// advisory duplicate check (CR-010): a possible match is never a hard block —
// emails are legitimately shared (couples) and family names recur in a small town
async function nmCheckDuplicates() {
    const family = document.getElementById("nmFamily").value.trim();
    const firstEmail = (document.getElementById("nmEmails").value.split("\n")[0] || "").trim();
    const q = firstEmail.includes("@") ? firstEmail : family;
    const list = document.getElementById("nmDupResults");
    if (q.length < 2) { list.innerHTML = ""; list.hidden = true; return; }
    const response = await registerCall(`/admin/people?${new URLSearchParams({q, limit: "5"})}`);
    if (!response) return;
    const people = (await response.json()).people;
    list.innerHTML = "";
    for (const p of people) {
        const li = document.createElement("li");
        li.textContent = `Possible existing match: ${personName(p)} (#${p.id}) — `
            + "review in Register & renewals before proceeding, if unsure.";
        list.appendChild(li);
    }
    list.hidden = people.length === 0;
}

function nmPersonPayload(prefix) {
    const id = (suffix) => document.getElementById(prefix + suffix);
    const lines = (suffix) => id(suffix).value.split("\n").map(s => s.trim()).filter(Boolean);
    const value = (suffix) => id(suffix).value.trim() || null;
    return {
        title: value("Title"), givenName: value("Given"), familyName: value("Family"),
        preferredName: value("Preferred"), dateOfBirth: value("Dob"), notes: value("Notes"),
        emails: lines("Emails").map((email, i) => ({email, isPrimary: i === 0})),
        phones: lines("Phones").map((line, i) => {
            const [number, type] = [line.replace(/\s+(MOBILE|HOME|WORK)$/i, "").trim(),
                (line.match(/\s+(MOBILE|HOME|WORK)$/i) || [])[1]];
            return {number, type: type ? type.toUpperCase() : null, isPrimary: i === 0};
        }),
    };
}

function openSecondPersonForm() {
    const sp = nmSecondPerson;
    document.getElementById("spTitle").value = sp?.title || "";
    document.getElementById("spGiven").value = sp?.givenName || "";
    document.getElementById("spFamily").value = sp?.familyName || "";
    document.getElementById("spPreferred").value = sp?.preferredName || "";
    document.getElementById("spDob").value = sp?.dateOfBirth || "";
    document.getElementById("spEmails").value = (sp?.emails || []).map(e => e.email).join("\n");
    document.getElementById("spPhones").value =
        (sp?.phones || []).map(ph => ph.number + (ph.type ? " " + ph.type : "")).join("\n");
    document.getElementById("spNotes").value = sp?.notes || "";
    document.getElementById("spRelationship").value = sp?.relationship || "PARTNER";
    openDialog("secondPersonForm");
}

function saveSecondPerson() {
    const payload = nmPersonPayload("sp");
    if (!payload.givenName || !payload.familyName) {
        return say("Given name and family name are required for the second person.", true);
    }
    payload.relationship = document.getElementById("spRelationship").value;
    nmSecondPerson = payload;
    closeDialog("secondPersonForm");
    nmRenderStatus();
}

function nmResetForm() {
    document.getElementById("nmSuccess").hidden = true;
    document.getElementById("nmForm").hidden = false;
    for (const suffix of ["Title", "Given", "Family", "Preferred", "Dob", "Emails", "Phones", "Notes"]) {
        document.getElementById("nm" + suffix).value = "";
    }
    const hn = document.getElementById("nmHouseholdName");
    hn.value = "";
    delete hn.dataset.touched;
    document.getElementById("nmDupResults").hidden = true;
    nmSecondPerson = null;
    nmFillPeriods(); // also renders status + second-person summary
}

async function nmCreate() {
    const person = nmPersonPayload("nm");
    if (!person.givenName || !person.familyName) {
        return say("Given name and family name are required.", true);
    }
    const periodId = Number(document.getElementById("nmPeriod").value);
    const typeId = Number(document.getElementById("nmType").value);
    if (!periodId || !typeId) return say("Pick a period and a membership type.", true);
    const body = {
        person,
        householdName: document.getElementById("nmHouseholdName").value.trim() || null,
        membershipPeriodId: periodId,
        membershipTypeId: typeId,
    };
    if (nmSecondPerson) body.secondPerson = nmSecondPerson;
    const response = await registerCall("/admin/new-member", {
        method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(body),
    });
    if (!response) return;
    nmShowSuccess(await response.json(), periodId);
}

function nmShowSuccess(result, periodId) {
    document.getElementById("nmForm").hidden = true;
    const el = document.getElementById("nmSuccess");
    el.innerHTML = "";
    el.hidden = false;
    const line = (html) => { const p = document.createElement("p"); p.innerHTML = html; el.appendChild(p); };
    line(`Created household #${result.householdId} and membership #${result.membershipId} — `
        + `${statusLabel(result.status)}, due ${dollars(result.amountDueCents)}.`);
    for (const w of result.warnings) line(`<span class="warn-note">${w}</span>`);
    line(`<a href="index.html?household=${result.householdId}">Open household</a> · `
        + `<a href="index.html?membership=${result.membershipId}&period=${periodId}">`
        + "Open membership (renewals — record a payment)</a>");
    const pay = document.createElement("button");
    pay.type = "button";
    pay.textContent = "Copy pay link";
    pay.onclick = () => copyPayLink(result.membershipId);
    el.appendChild(pay);
    const again = document.createElement("button");
    again.type = "button";
    again.className = "secondary";
    again.textContent = "Add another member";
    again.onclick = nmResetForm;
    el.appendChild(again);
}

function wireNewMember() {
    document.getElementById("nmFamily").onblur = () => {
        const hn = document.getElementById("nmHouseholdName");
        if (!hn.dataset.touched) {
            const family = document.getElementById("nmFamily").value.trim();
            hn.value = family ? `${family} household` : "";
        }
        nmCheckDuplicates();
    };
    document.getElementById("nmHouseholdName").oninput = () =>
        { document.getElementById("nmHouseholdName").dataset.touched = "1"; };
    document.getElementById("nmEmails").onblur = nmCheckDuplicates;
    document.getElementById("nmPeriod").onchange = nmFillTypes;
    document.getElementById("nmType").onchange = nmOnTypeChanged;
    document.getElementById("nmAddSecond").onclick = () => openSecondPersonForm();
    document.getElementById("spSave").onclick = saveSecondPerson;
    document.getElementById("spCancel").onclick = () => closeDialog("secondPersonForm");
    document.getElementById("nmCreate").onclick = nmCreate;
    document.getElementById("newMemberSection").hidden = false;
}

// ---- communication preferences (CR-005) ------------------------------------

const COMM_TYPES = ["NEWSLETTER", "RENEWAL", "EVENTS", "GENERAL"];
const DELIVERY_METHODS = ["EMAIL", "POST", "SMS", "NONE"];

// a compact per-scope preferences table (communication type × delivery); the
// effective value shows its source (person / household / default), and a value
// that only inherits is styled muted so a real override stands out
async function renderPreferences(containerId, scope, id) {
    const container = document.getElementById(containerId);
    if (!container) return;
    container.innerHTML = "";
    const response = await registerCall(`/admin/${scope}/${id}/preferences`);
    if (!response) return;
    const prefs = (await response.json()).preferences;
    const table = document.createElement("table");
    const head = table.createTHead().insertRow();
    for (const label of ["Communication", "Delivery", "Source"]) {
        const th = document.createElement("th");
        th.textContent = label;
        head.appendChild(th);
    }
    const tbody = table.createTBody();
    for (const type of COMM_TYPES) {
        const row = tbody.insertRow();
        row.insertCell().textContent = type;
        const current = prefs[type] || {method: "EMAIL", source: "default"};
        const select = document.createElement("select");
        for (const m of DELIVERY_METHODS) {
            const o = document.createElement("option");
            o.value = o.textContent = m;
            o.selected = m === current.method;
            select.appendChild(o);
        }
        const sourceCell = document.createElement("td");
        const setSource = (src) => {
            sourceCell.textContent = src;
            select.classList.toggle("muted", src === "default" || src === "household");
        };
        setSource(current.source);
        select.onchange = async () => {
            const r = await registerCall(`/admin/${scope}/${id}/preferences`, {
                method: "PUT", headers: {"Content-Type": "application/json"},
                body: JSON.stringify({communicationType: type, deliveryMethod: select.value}),
            });
            if (!r) return;
            say(`${type} → ${select.value}.`);
            renderPreferences(containerId, scope, id); // re-read: source may now be person/household
        };
        row.insertCell().appendChild(select);
        row.appendChild(sourceCell);
    }
    container.appendChild(table);
}

// ---- email: templates, compose, send log (CR-005) ---------------------------

let emFields = [];
let editingTemplateId = null;
let lastTemplateField = "tplBody"; // which input the merge-field chips insert into
let emPreviewSig = null;           // params last previewed; Send is gated on a match
let sendPollTimer = null;

async function loadEmail() {
    const response = await registerCall("/admin/email/templates");
    if (!response) return;
    const data = await response.json();
    emFields = data.fields;
    document.getElementById("mailDisabledBanner").hidden = !!data.mailEnabled;
    renderTemplates(data.templates);
    const tplSelect = document.getElementById("emTemplate");
    tplSelect.innerHTML = "";
    for (const t of data.templates) {
        const o = document.createElement("option");
        o.value = t.id;
        o.textContent = t.name;
        tplSelect.appendChild(o);
    }
    renderFieldChips();
}

function renderTemplates(templates) {
    const body = document.querySelector("#templates tbody");
    body.innerHTML = "";
    for (const t of templates) {
        const row = body.insertRow();
        row.insertCell().textContent = t.name;
        row.insertCell().textContent = t.subject;
        row.insertCell().textContent = t.updatedAt.slice(0, 10);
        const cell = row.insertCell();
        const edit = document.createElement("button");
        edit.textContent = "Edit";
        edit.onclick = () => openTemplateForm(t);
        const del = document.createElement("button");
        del.textContent = "Delete";
        del.className = "secondary";
        del.style.marginLeft = ".4rem";
        del.onclick = () => deleteTemplate(t);
        cell.append(edit, del);
    }
}

function renderFieldChips() {
    const box = document.getElementById("tplFields");
    box.innerHTML = "";
    for (const f of emFields) {
        const chip = document.createElement("button");
        chip.type = "button";
        chip.className = "secondary field-chip";
        chip.textContent = `{{${f}}}`;
        chip.onclick = () => insertMergeField(f);
        box.appendChild(chip);
    }
}

function insertMergeField(field) {
    const el = document.getElementById(lastTemplateField);
    const token = `{{${field}}}`;
    const start = el.selectionStart ?? el.value.length;
    const end = el.selectionEnd ?? el.value.length;
    el.value = el.value.slice(0, start) + token + el.value.slice(end);
    el.focus();
    el.selectionStart = el.selectionEnd = start + token.length;
}

function openTemplateForm(t) {
    editingTemplateId = t ? t.id : null;
    document.getElementById("tplFormTitle").textContent = t ? `Edit template “${t.name}”` : "New template";
    document.getElementById("tplName").value = t?.name || "";
    document.getElementById("tplSubject").value = t?.subject || "";
    document.getElementById("tplBody").value = t?.body || "";
    openDialog("templateForm");
}

async function saveTemplate() {
    const name = document.getElementById("tplName").value.trim();
    const subject = document.getElementById("tplSubject").value.trim();
    const body = document.getElementById("tplBody").value;
    if (!name || !subject || !body.trim()) return say("Name, subject and body are all required.", true);
    const path = editingTemplateId ? `/admin/email/templates/${editingTemplateId}` : "/admin/email/templates";
    const response = await registerCall(path, {
        method: editingTemplateId ? "PUT" : "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({name, subject, body}),
    });
    if (!response) return;
    const t = await response.json();
    say(`Saved template “${t.name}”.`);
    closeDialog("templateForm");
    loadEmail();
}

async function deleteTemplate(t) {
    if (!confirm(`Delete template “${t.name}”? Past sends keep their snapshot.`)) return;
    const response = await registerCall(`/admin/email/templates/${t.id}`, {method: "DELETE"});
    if (!response) return;
    say(`Deleted template “${t.name}”.`);
    loadEmail();
}

async function testSendTemplate() {
    if (!editingTemplateId) return say("Save the template first, then send a test.", true);
    const to = prompt("Send a test to which address?", adminEmail || "");
    if (!to) return;
    const response = await registerCall(`/admin/email/templates/${editingTemplateId}/test`, {
        method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify({to}),
    });
    if (!response) return;
    const r = await response.json();
    say(`Test email queued to ${to}. Subject: ${r.subject}`);
}

// ---- compose / preview / send ----------------------------------------------

function emComposeParams() {
    const typeVal = document.getElementById("emType").value;
    return {
        templateId: Number(document.getElementById("emTemplate").value) || null,
        periodId: Number(document.getElementById("emPeriod").value) || null,
        statusFilter: document.getElementById("emStatus").value || null,
        typeFilter: typeVal ? Number(typeVal) : null,
        communicationType: document.getElementById("emCommType").value,
        footer: document.getElementById("emFooter").value,
    };
}

// any compose change invalidates a prior preview — Send re-locks until the
// exact same parameters have been previewed again
function invalidatePreview() {
    emPreviewSig = null;
    const send = document.getElementById("emSend");
    send.disabled = true;
    send.textContent = "Send";
}

async function previewSend() {
    const params = emComposeParams();
    if (!params.templateId || !params.periodId) return say("Pick a template and a period.", true);
    const response = await registerCall("/admin/email/preview", {
        method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(params),
    });
    if (!response) return;
    const report = await response.json();
    renderPreviewReport(report);
    emPreviewSig = JSON.stringify(params);
    const send = document.getElementById("emSend");
    send.disabled = report.counts.toSend === 0;
    send.textContent = `Send ${report.counts.toSend} email${report.counts.toSend === 1 ? "" : "s"}`;
    if (report.counts.toSend === 0) say("Nobody in this segment can be emailed — nothing to send.", true);
}

function renderPreviewReport(report) {
    const el = document.getElementById("emPreviewReport");
    el.innerHTML = "";
    const line = (html) => { const p = document.createElement("p"); p.innerHTML = html; el.appendChild(p); };
    const c = report.counts;
    line(`<strong>${c.memberships}</strong> membership(s) in this segment → `
        + `<strong>${c.toSend}</strong> to email, ${c.skippedPost} post, `
        + `${c.skippedNone} opted out, ${c.noEmail} with no email.`);
    if (report.smsWarning) line(`<span class="warn-note">Some members have an SMS preference — `
        + `SMS is not implemented, so they are treated as opted out.</span>`);
    emList(el, "To email", report.toSend, r => `${r.personName} · ${r.email} (${r.displayName})`);
    emList(el, "Skipped — post", report.skippedPost, r => `${r.personName} (${r.displayName})`);
    emList(el, "Skipped — opted out", report.skippedNone, r => `${r.personName} (${r.displayName})`);
    emList(el, "No email on file", report.noEmail, r => r.displayName);
    if (report.sample) {
        const h = document.createElement("h4");
        h.textContent = "Sample (first recipient)";
        el.appendChild(h);
        const pre = document.createElement("pre");
        pre.className = "mail-sample";
        pre.textContent = `Subject: ${report.sample.subject}\n\n${report.sample.body}`;
        el.appendChild(pre);
    }
}

function emList(parent, title, rows, fmt) {
    if (!rows.length) return;
    const details = document.createElement("details");
    const summary = document.createElement("summary");
    summary.textContent = `${title} (${rows.length})`;
    details.appendChild(summary);
    const ul = document.createElement("ul");
    for (const r of rows) {
        const li = document.createElement("li");
        li.textContent = fmt(r);
        ul.appendChild(li);
    }
    details.appendChild(ul);
    parent.appendChild(details);
}

async function doSend() {
    const params = emComposeParams();
    if (emPreviewSig !== JSON.stringify(params)) {
        return say("Preview these exact parameters before sending.", true);
    }
    if (!confirm(document.getElementById("emSend").textContent + "?")) return;
    // "save as default footer" is a separate PUT, not a send parameter
    if (document.getElementById("emFooterSave").checked) {
        await registerCall("/admin/email/footer", {
            method: "PUT", headers: {"Content-Type": "application/json"},
            body: JSON.stringify({text: params.footer}),
        });
    }
    const response = await registerCall("/admin/email/sends", {
        method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(params),
    });
    if (!response) return;
    const {id} = await response.json();
    say(`Send #${id} started.`);
    invalidatePreview();
    await renderSends();
    openSend(id);
}

async function renderSends() {
    const response = await registerCall("/admin/email/sends");
    if (!response) return;
    const sends = (await response.json()).sends;
    const body = document.querySelector("#sends tbody");
    body.innerHTML = "";
    for (const s of sends) {
        const row = body.insertRow();
        row.insertCell().textContent = s.id;
        row.insertCell().textContent = s.createdAt.slice(0, 16).replace("T", " ");
        row.insertCell().textContent = s.templateName || "(deleted)";
        row.insertCell().textContent = `${s.periodName}${s.statusFilter ? " · " + statusLabel(s.statusFilter) : ""}`
            + `${s.typeName ? " · " + s.typeName : ""} · ${s.communicationType}`;
        row.insertCell().textContent = emCountsText(s.counts);
        row.insertCell().appendChild(sendStatusBadge(s.status));
        const open = document.createElement("button");
        open.textContent = "Open";
        open.onclick = () => openSend(s.id);
        row.insertCell().appendChild(open);
    }
}

function emCountsText(counts) {
    return Object.entries(counts).map(([k, v]) => `${emStatusLabel(k)} ${v}`).join(", ") || "—";
}

const EM_STATUS_LABELS = {
    SENT: "sent", FAILED: "failed", PENDING: "pending",
    SKIPPED_POST: "post", SKIPPED_NONE: "opted out", NO_EMAIL: "no email",
};
const emStatusLabel = (s) => EM_STATUS_LABELS[s] || s;

function sendStatusBadge(status) {
    const span = document.createElement("span");
    const colour = status === "COMPLETE" ? "green" : status === "RUNNING" ? "blue" : "amber";
    span.className = `badge badge-${colour}`;
    span.textContent = status;
    return span;
}

async function openSend(id) {
    clearTimeout(sendPollTimer);
    const response = await registerCall(`/admin/email/sends/${id}`);
    if (!response) return;
    const s = await response.json();
    document.getElementById("sendDetailTitle").textContent = `Send #${s.id} — ${s.status}`;
    document.getElementById("sendDetailSummary").textContent =
        `${s.templateName || "(deleted template)"} · ${s.periodName} · ${s.communicationType}. `
        + emCountsText(s.counts);
    const actions = document.getElementById("sendDetailActions");
    actions.innerHTML = "";
    if (s.status !== "RUNNING") {
        const pending = (s.counts.PENDING || 0) + (s.counts.FAILED || 0);
        if (pending > 0) {
            const resume = document.createElement("button");
            resume.textContent = `Resume (${pending} pending/failed)`;
            resume.onclick = () => resumeSend(id);
            actions.appendChild(resume);
        }
    }
    const body = document.querySelector("#sendRecipients tbody");
    body.innerHTML = "";
    for (const r of s.recipients) {
        const row = body.insertRow();
        row.insertCell().textContent = r.displayName;
        row.insertCell().textContent = r.personName || "—";
        row.insertCell().textContent = r.email || "—";
        row.insertCell().appendChild(recipientBadge(r.status));
        row.insertCell().textContent = r.error || (r.sentAt ? r.sentAt.slice(0, 19).replace("T", " ") : "");
    }
    openDialog("sendDetail");
    if (s.status === "RUNNING") sendPollTimer = setTimeout(() => openSend(id), 1500); // live progress
    else renderSends();
}

function recipientBadge(status) {
    const span = document.createElement("span");
    const colour = status === "SENT" ? "green" : status === "FAILED" ? "amber"
        : status === "PENDING" ? "blue" : "grey";
    span.className = `badge badge-${colour}`;
    span.textContent = emStatusLabel(status);
    return span;
}

async function resumeSend(id) {
    const response = await registerCall(`/admin/email/sends/${id}/resume`, {method: "POST"});
    if (!response) return;
    say(`Resumed send #${id}.`);
    openSend(id);
}

async function loadFooter() {
    const response = await registerCall("/admin/email/footer");
    if (!response) return;
    document.getElementById("emFooter").value = (await response.json()).text;
}

function wireEmail() {
    const on = (id, handler) => { document.getElementById(id).onclick = handler; };
    on("tplNew", () => openTemplateForm(null));
    on("tplSave", saveTemplate);
    on("tplTest", testSendTemplate);
    on("tplCancel", () => closeDialog("templateForm"));
    for (const f of ["tplSubject", "tplBody"]) {
        document.getElementById(f).addEventListener("focus", () => { lastTemplateField = f; });
    }
    on("emPreview", previewSend);
    on("emSend", doSend);
    on("emRefreshLog", renderSends);
    on("sendDetailClose", () => { clearTimeout(sendPollTimer); closeDialog("sendDetail"); });
    for (const id of ["emTemplate", "emPeriod", "emStatus", "emType", "emCommType", "emFooter"]) {
        document.getElementById(id).addEventListener("input", invalidatePreview);
    }
    document.getElementById("emailSection").hidden = false;
}

// the compose period/type selects (email page has no Renewals table of its own)
function emFillSelects() {
    const periodSel = document.getElementById("emPeriod");
    if (!periodSel) return;
    periodSel.innerHTML = "";
    const todayStr = today();
    const covering = periodsCache.find(p => p.startDate <= todayStr && todayStr <= p.endDate);
    const defaultId = covering ? covering.id : (periodsCache[0] && periodsCache[0].id);
    for (const p of periodsCache) {
        const o = document.createElement("option");
        o.value = p.id;
        o.textContent = p.name;
        o.selected = p.id === defaultId;
        periodSel.appendChild(o);
    }
    const typeSel = document.getElementById("emType");
    typeSel.innerHTML = "";
    const all = document.createElement("option");
    all.value = "";
    all.textContent = "All";
    typeSel.appendChild(all);
    for (const t of allTypes()) {
        const o = document.createElement("option");
        o.value = t.typeId;
        o.textContent = t.type;
        typeSel.appendChild(o);
    }
}

// ---- menu + per-page wiring -------------------------------------------------

// the admin panel is split across pages that share this script; each page
// carries only its own sections, and the boot wires whatever is present
const MENU = [
    {href: "index.html", label: "Register & renewals"},
    {href: "new-member.html", label: "New member"},
    {href: "email.html", label: "Email"},
    {href: "import.html", label: "Import members"},
    {href: "users.html", label: "Users"},
];

function renderMenu() {
    const nav = document.getElementById("menu");
    if (!nav) return;
    const here = location.pathname.split("/").pop() || "index.html"; // "" at /admin/ → index
    nav.innerHTML = "";
    for (const item of MENU) {
        const a = document.createElement("a");
        a.href = item.href;
        a.textContent = item.label;
        if (item.href === here) a.className = "active";
        nav.appendChild(a);
    }
}

async function wireUsers() {
    document.getElementById("userSearchGo").onclick = renderUsers;
    document.getElementById("userSearch").onkeydown =
        (e) => { if (e.key === "Enter") renderUsers(); };
    if (document.getElementById("ssPreview")) wireSelfServe();
    await renderUsers();
}

// ---- boot ---------------------------------------------------------------

(async () => {
    try {
        await Auth.completeLoginIfReturning();
        if (!Auth.hasToken()) return await Auth.login(); // await: reach the catch below
        if (await showIdentity()) {
            renderMenu();
            if (document.getElementById("usersSection")) await wireUsers();
            if (document.getElementById("importSection")) { wireImport(); await loadPeriods(); }
            if (document.getElementById("newMemberSection")) {
                wireNewMember();
                await loadPeriods();
                nmResetForm();
            }
            if (document.getElementById("emailSection")) {
                wireEmail();
                await loadPeriods(); // fills periodsCache (no Renewals table on this page)
                emFillSelects();
                await loadEmail();
                await loadFooter();
                await renderSends();
            }
            // CR-010 success-screen deep links: index.html?household=<id> and
            // ?membership=<id>&period=<id> open straight to that detail dialog
            const params = new URLSearchParams(location.search);
            if (document.getElementById("registerSection")) {
                wireRegister();
                await renderPeople();
                await renderHouseholds();
                if (params.has("household")) await openHousehold(Number(params.get("household")));
            }
            if (document.getElementById("renewalsSection")) {
                wireRenewals();
                await loadPeriods(params.has("period") ? Number(params.get("period")) : undefined);
                if (params.has("membership")) await openMembership(Number(params.get("membership")));
            }
        }
    } catch (e) {
        statusBox.textContent = "Login failed: " + e;
        statusBox.className = "warn";
    }
})();
