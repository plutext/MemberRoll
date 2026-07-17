/* The user webapp shell: sign in/out, identity line, the mandatory
 * role-claim gate, and the notes example wired to /api/notes. This file
 * is the copy-me pattern for pages: boot completes a returning login,
 * renders signed-out vs signed-in, and every server call goes through
 * Auth.api (bearer attached, one refresh-and-retry, honest 401 surfacing).
 */
"use strict";

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

function statusButton(label, onclick) {
    const btn = document.createElement("button");
    btn.textContent = label;
    btn.style.marginLeft = "1rem";
    btn.onclick = onclick;
    statusBox.appendChild(btn);
}

// ---- identity + claim gate ------------------------------------------------

async function showIdentity() {
    const response = await Auth.api("/whoami");
    if (!response) return false;
    const who = await response.json();
    statusBox.textContent = `Signed in as ${who.username}`
        + (who.claimed_role ? ` (${Claim.LABELS[who.claimed_role] || who.claimed_role}`
            + `${who.verified ? ", verified" : ""})` : "");
    statusBox.className = "";
    if (who.roles.includes("admin")) {
        const link = document.createElement("a");
        link.href = "../admin/";
        link.textContent = "Admin panel";
        link.style.marginLeft = "1rem";
        statusBox.appendChild(link);
    }
    statusButton("Change role", () => Claim.prompt(who.claimed_role, showIdentity));
    statusButton("Sign out", Auth.logout);
    if (!who.claimed_role) {
        // mandatory: accounts that predate (or skipped) the registration
        // picker choose now; the modal has no dismiss
        Claim.prompt(null, showIdentity);
    }
    return true;
}

function showSignedOut() {
    statusBox.textContent = "Not signed in.";
    statusBox.className = "";
    statusButton("Sign in / register", () =>
        Auth.login().catch(e => say(e.message, true)));
}

// ---- notes example ----------------------------------------------------------

async function renderNotes() {
    const response = await Auth.api("/notes");
    if (!response || !response.ok) return;
    const notes = await response.json();
    const list = document.getElementById("notes");
    list.innerHTML = "";
    for (const note of notes) {
        const item = document.createElement("li");
        const heading = document.createElement("h3");
        heading.textContent = `${note.title} (${note.id})`;
        const body = document.createElement("p");
        body.textContent = note.body;
        const edit = document.createElement("button");
        edit.textContent = "Edit";
        edit.onclick = () => {
            document.getElementById("noteId").value = note.id;
            document.getElementById("noteTitle").value = note.title;
            document.getElementById("noteBody").value = note.body;
        };
        const del = document.createElement("button");
        del.textContent = "Delete";
        del.style.marginLeft = ".5rem";
        del.onclick = async () => {
            const r = await Auth.api(`/notes/${note.id}`, {method: "DELETE"});
            if (r) say(r.ok ? `Deleted ${note.id}.` : `Delete failed: HTTP ${r.status}`, !r.ok);
            renderNotes();
        };
        item.append(heading, body, edit, del);
        list.appendChild(item);
    }
}

async function saveNote() {
    const id = document.getElementById("noteId").value.trim();
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(id)) return say("Note id: letters, digits, - _", true);
    const response = await Auth.api(`/notes/${id}`, {
        method: "PUT",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
            title: document.getElementById("noteTitle").value,
            body: document.getElementById("noteBody").value,
        }),
    });
    if (!response) return;
    say(response.ok ? `Saved ${id}.` : `Save failed: HTTP ${response.status}`, !response.ok);
    renderNotes();
}

// ---- boot ---------------------------------------------------------------

(async () => {
    try {
        await Auth.completeLoginIfReturning();
        if (!Auth.hasToken()) {
            showSignedOut();
            return;
        }
        if (await showIdentity()) {
            document.getElementById("editor").hidden = false;
            document.getElementById("noteSave").onclick = saveNote;
            await renderNotes();
        }
    } catch (e) {
        statusBox.textContent = "Login failed: " + e;
        statusBox.className = "warn";
    }
})();
