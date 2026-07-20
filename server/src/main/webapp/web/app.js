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
        // an ACTIVE membership carries a card (CR-017); the server is the
        // authority (it 404s a card for a non-ACTIVE one), so this is a hint
        // that saves a certain-to-404 fetch — renderCard re-checks either way
        if (m.status === "ACTIVE") renderCard(card, m.membershipId);
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

// ---- membership card (CR-017) ----------------------------------------------

/* The bearer-auth bite (recorded in the CR): a plain <img src="/api/me/..."> sends
   NO Authorization header and 401s. So we fetch the PNG through Auth.api (bearer
   attached), wrap the bytes in ONE blob URL, and hang the <img>, the download
   link and the print pop-up all off that single blob. */
async function renderCard(container, membershipId) {
    // info first — it decides which buttons the page shows (and 404s for a
    // membership with no card, e.g. one that just lapsed since the list loaded)
    const infoResp = await Auth.api(`/me/membership/${membershipId}/card/info`);
    if (!infoResp || !infoResp.ok) return; // no card — nothing to show, silently
    const info = await infoResp.json();
    const pngResp = await Auth.api(`/me/membership/${membershipId}/card`);
    if (!pngResp || !pngResp.ok) return;
    const blobUrl = URL.createObjectURL(await pngResp.blob());

    const wrap = document.createElement("div");
    wrap.className = "card-region";
    const h = document.createElement("h4");
    h.textContent = "Membership card";
    const img = document.createElement("img");
    img.className = "card-image";
    img.alt = "Membership card";
    img.src = blobUrl;
    wrap.append(h, img);

    const actions = document.createElement("div");
    actions.className = "card-actions";
    // Download — the same blob, named from the server (period-stamped)
    const dl = document.createElement("a");
    dl.textContent = "Download";
    dl.href = blobUrl;
    dl.download = info.filename;
    dl.setAttribute("role", "button");
    dl.className = "secondary";
    actions.appendChild(dl);
    // Print — a bare pop-up holding only the card at true size
    const pr = document.createElement("button");
    pr.textContent = "Print";
    pr.className = "secondary";
    pr.onclick = () => printCard(blobUrl);
    actions.appendChild(pr);
    // Email me my card — only when mail is configured and we have their address
    if (info.mailEnabled && info.emailTo) {
        const em = document.createElement("button");
        em.textContent = "Email me my card";
        em.onclick = () => emailMyCard(membershipId, em);
        actions.appendChild(em);
    } else {
        const hint = document.createElement("span");
        hint.className = "muted";
        hint.textContent = info.mailEnabled
            ? "No email on file — download or print your card."
            : "Email is not set up here — download or print your card.";
        actions.appendChild(hint);
    }
    wrap.appendChild(actions);
    container.appendChild(wrap);
}

// print: a bare same-origin pop-up holding only the card image, CSS-sized to
// its true 85.6mm width with crop marks, then window.print() — but only AFTER
// the image's load event (printing before decode yields a blank card)
function printCard(blobUrl) {
    const w = window.open("", "_blank");
    if (!w) {
        say("Pop-up blocked — allow pop-ups for this site to print your card.", true);
        return;
    }
    w.document.title = "Membership card";
    const style = w.document.createElement("style");
    style.textContent =
        "@page { margin: 14mm } html,body { margin: 0 }"
        + " .box { position: relative; width: 85.6mm; height: 54mm; margin: 8mm auto; }"
        + " .box img { width: 85.6mm; height: 54mm; display: block; }"
        // four L-shaped crop marks, one per corner, just outside the card edge
        + " .m { position: absolute; background: #000; }"
        + " .h { width: 4mm; height: .2mm } .v { width: .2mm; height: 4mm }"
        + " .tl-h{top:-2mm;left:-5mm}.tl-v{top:-5mm;left:-2mm}"
        + " .tr-h{top:-2mm;right:-5mm}.tr-v{top:-5mm;right:-2mm}"
        + " .bl-h{bottom:-2mm;left:-5mm}.bl-v{bottom:-5mm;left:-2mm}"
        + " .br-h{bottom:-2mm;right:-5mm}.br-v{bottom:-5mm;right:-2mm}";
    w.document.head.appendChild(style);
    const box = w.document.createElement("div");
    box.className = "box";
    const img = w.document.createElement("img");
    img.src = blobUrl; // same-origin pop-up can read the opener's blob URL
    box.appendChild(img);
    for (const c of ["tl-h h", "tl-v v", "tr-h h", "tr-v v",
                     "bl-h h", "bl-v v", "br-h h", "br-v v"]) {
        const mark = w.document.createElement("div");
        mark.className = "m " + c;
        box.appendChild(mark);
    }
    w.document.body.appendChild(box);
    w.document.close();
    // wait for the image to decode before printing
    if (img.complete) { w.focus(); w.print(); }
    else img.onload = () => { w.focus(); w.print(); };
}

async function emailMyCard(membershipId, button) {
    button.disabled = true;
    try {
        const resp = await Auth.api(`/me/membership/${membershipId}/card/email`, {method: "POST"});
        if (!resp) return;
        if (!resp.ok) {
            say(`Could not email your card (HTTP ${resp.status}).`, true);
            return;
        }
        const {sentTo} = await resp.json();
        say(`Your membership card has been emailed to ${sentTo}.`);
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
