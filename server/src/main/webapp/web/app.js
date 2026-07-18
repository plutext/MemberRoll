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

/* The member webapp: sign in/out, identity line, the mandatory role-claim
 * gate, and the CR-006 "my membership" view. Boot completes a returning
 * login, renders signed-out vs signed-in, and every server call goes
 * through Auth.api (bearer attached, one refresh-and-retry, honest 401
 * surfacing). Pay now is a handoff: POST pay-link, then navigate to the
 * CR-004 pay page — one Stripe surface, unchanged.
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
        // picker choose now; the modal has no dismiss. Provisioned accounts
        // (CR-006) arrive with claimed_role=member, so it never pops for them.
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

// ---- my membership (CR-006) -------------------------------------------------

const STATUS_LABELS = {
    PENDING_PAYMENT: "Unpaid", ACTIVE: "Paid", LAPSED: "Lapsed",
    APPLIED: "Applied", CEASED: "Ceased",
};
const STATUS_BADGE = {
    ACTIVE: "green", PENDING_PAYMENT: "amber", APPLIED: "blue",
    LAPSED: "grey", CEASED: "grey",
};
const dollars = (cents) => "$" + (cents / 100).toFixed(2);

function statusBadge(status) {
    const span = document.createElement("span");
    span.className = `badge badge-${STATUS_BADGE[status] || "grey"}`;
    span.textContent = STATUS_LABELS[status] || status;
    return span;
}

async function renderMembership() {
    const response = await Auth.api("/me/membership");
    if (!response) return;
    if (!response.ok) {
        say(`Could not load your membership (HTTP ${response.status}).`, true);
        return;
    }
    const data = await response.json();
    document.getElementById("membershipSection").hidden = false;
    const notLinked = document.getElementById("notLinked");
    const linked = document.getElementById("linked");
    if (!data.linked) {
        // deliberately no lookup form — "contact the society" is the answer
        notLinked.textContent = "We couldn't find a membership linked to this "
            + `account — contact ${data.societyName}.`;
        notLinked.hidden = false;
        linked.hidden = true;
        return;
    }
    notLinked.hidden = true;
    linked.hidden = false;

    const list = document.getElementById("memberships");
    list.innerHTML = "";
    if (data.memberships.length === 0) {
        const p = document.createElement("p");
        p.textContent = "No current membership. Contact "
            + `${data.societyName} if you were expecting one.`;
        list.appendChild(p);
    }
    for (const m of data.memberships) {
        const card = document.createElement("div");
        card.className = "membership-card";
        const heading = document.createElement("h3");
        heading.append(`${m.displayName} — ${m.periodName} `, statusBadge(m.status));
        const balance = Math.max(0, m.amountDueCents - m.amountPaidCents);
        const amounts = document.createElement("p");
        amounts.textContent = `${m.typeName} membership. `
            + (balance > 0
                ? `Due ${dollars(m.amountDueCents)}, paid ${dollars(m.amountPaidCents)} — `
                    + `balance ${dollars(balance)}.`
                : "Paid up — thank you!");
        card.append(heading, amounts);
        if (balance > 0 && m.status !== "CEASED") {
            const pay = document.createElement("button");
            pay.textContent = "Pay now";
            pay.onclick = () => payNow(m.membershipId, pay);
            card.appendChild(pay);
        }
        list.appendChild(card);
    }

    const historyWrap = document.getElementById("historyWrap");
    historyWrap.hidden = data.history.length === 0;
    const tbody = document.querySelector("#history tbody");
    tbody.innerHTML = "";
    for (const h of data.history) {
        const row = tbody.insertRow();
        row.insertCell().textContent = h.periodName;
        row.insertCell().textContent = h.typeName;
        row.insertCell().appendChild(statusBadge(h.status));
    }
}

async function payNow(membershipId, button) {
    button.disabled = true; // no double-mint on a slow network (a re-click would still be safe)
    try {
        const response = await Auth.api(`/me/membership/${membershipId}/pay-link`, {method: "POST"});
        if (!response) return;
        if (!response.ok) {
            say(`Could not start the payment (HTTP ${response.status}).`, true);
            return;
        }
        const {url} = await response.json();
        location.href = url; // the CR-004 pay page takes it from here
    } finally {
        button.disabled = false;
    }
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
            await renderMembership();
        }
    } catch (e) {
        statusBox.textContent = "Login failed: " + e;
        statusBox.className = "warn";
    }
})();
