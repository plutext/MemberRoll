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
        }
    } catch (e) {
        statusBox.textContent = "Login failed: " + e;
        statusBox.className = "warn";
    }
})();
