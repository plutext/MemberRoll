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

        // verified: meaningful only alongside a claim
        const verifiedCell = row.insertCell();
        const verified = document.createElement("input");
        verified.type = "checkbox";
        verified.checked = u.verified;
        verified.disabled = !u.claimed_role;
        verified.title = u.claimed_role
            ? "The claim has been checked as fact"
            : "No claim to verify";
        verified.onchange = async () => {
            if (await userAction(`/admin/users/${u.id}/verified`,
                    {verified: verified.checked})) {
                say(`${u.username}: ${verified.checked ? "verified" : "verification removed"}.`);
            }
            renderUsers();
        };
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
    document.getElementById("personForm").hidden = false;
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
    document.getElementById("personForm").hidden = true;
    renderPeople();
}

// ---- register: households ---------------------------------------------------

let openHouseholdId = null;

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
    document.getElementById("householdDetail").hidden = false;
}

async function saveHousehold() {
    const name = document.getElementById("hfName").value.trim() || null;
    const contact = document.getElementById("hfContact").value.trim();
    if (!contact) return say("Primary contact person id is required.", true);
    const response = await registerCall("/admin/households", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({householdName: name, primaryContactPersonId: Number(contact)}),
    });
    if (!response) return;
    const household = await response.json();
    say(`Created household #${household.id}.`);
    document.getElementById("householdForm").hidden = true;
    renderHouseholds();
}

async function addHouseholdMember() {
    const personId = document.getElementById("hdPersonId").value.trim();
    if (!personId || openHouseholdId === null) return say("Person id is required.", true);
    const relationship = document.getElementById("hdRelationship").value;
    if (await registerCall(`/admin/households/${openHouseholdId}/people`, {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({personId: Number(personId), relationshipType: relationship}),
    })) {
        say(`Added person #${personId}.`);
        document.getElementById("hdPersonId").value = "";
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

function wireRegister() {
    const on = (id, handler) => { document.getElementById(id).onclick = handler; };
    const enter = (id, handler) =>
        { document.getElementById(id).onkeydown = (e) => { if (e.key === "Enter") handler(); }; };
    on("importPreview", previewImport);
    on("importApply", applyImport);
    on("personSearchGo", renderPeople);      enter("personSearch", renderPeople);
    on("personNew", () => openPersonForm(null));
    on("personSave", savePerson);
    on("personCancel", () => { document.getElementById("personForm").hidden = true; });
    on("householdSearchGo", renderHouseholds); enter("householdSearch", renderHouseholds);
    on("householdNew", () => { document.getElementById("householdForm").hidden = false; });
    on("householdSave", saveHousehold);
    on("householdCancel", () => { document.getElementById("householdForm").hidden = true; });
    on("hdAdd", addHouseholdMember);
    on("hdClose", () => { document.getElementById("householdDetail").hidden = true; });
    document.getElementById("registerSection").hidden = false;
}

// ---- boot ---------------------------------------------------------------

(async () => {
    try {
        await Auth.completeLoginIfReturning();
        if (!Auth.hasToken()) return await Auth.login(); // await: reach the catch below
        if (await showIdentity()) {
            document.getElementById("userSearchGo").onclick = renderUsers;
            document.getElementById("userSearch").onkeydown =
                (e) => { if (e.key === "Enter") renderUsers(); };
            await renderUsers();
            wireRegister();
            await renderPeople();
            await renderHouseholds();
        }
    } catch (e) {
        statusBox.textContent = "Login failed: " + e;
        statusBox.className = "warn";
    }
})();
