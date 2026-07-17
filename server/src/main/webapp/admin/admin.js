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

Auth.onFresh401 = (text) => {
    statusBox.textContent = text;
    statusBox.className = "warn";
};

function say(text, isError) {
    message.textContent = text;
    message.className = isError ? "error" : "";
}

// the edit forms and detail panels are <dialog> elements — open them as
// native modals (backdrop, Esc-to-close, focus trap), so no scroll-into-view
// juggling and no fighting the [hidden] rule. close() reverses it.
// openDialog is idempotent: openMembership/openHousehold re-open to refresh in
// place, and showModal() on an already-open dialog throws.
function openDialog(id) {
    const d = document.getElementById(id);
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
    openDialog("personForm");
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
    el.textContent = `${p.name}: ${p.startDate} → ${p.endDate}. Prices: ${prices || "—"}.`;
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
        amountCents: -p.amountCents,
        method: p.method === "STRIPE" ? "OTHER" : p.method, // STRIPE is never hand-entered
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

// ---- menu + per-page wiring -------------------------------------------------

// the admin panel is split across pages that share this script; each page
// carries only its own sections, and the boot wires whatever is present
const MENU = [
    {href: "index.html", label: "Register & renewals"},
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
            if (document.getElementById("registerSection")) {
                wireRegister();
                await renderPeople();
                await renderHouseholds();
            }
            if (document.getElementById("renewalsSection")) {
                wireRenewals();
                await loadPeriods();
            }
        }
    } catch (e) {
        statusBox.textContent = "Login failed: " + e;
        statusBox.className = "warn";
    }
})();
