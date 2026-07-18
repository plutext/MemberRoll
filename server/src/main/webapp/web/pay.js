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

/* The magic-link pay page (CR-004). Classic script, no auth.js — this page
 * never logs in; the ?t= token from the emailed link is the authorisation.
 * States: payable form (journal add-on + free-entry donation), returned
 * from Stripe (?paid=1: poll until the webhook lands), paid-up thanks,
 * ceased, and 404 with the lost-link form inline. */
"use strict";

const API = "../api";
const params = new URLSearchParams(location.search);
const token = params.get("t");
const returnedFromStripe = params.get("paid") === "1";

const el = (id) => document.getElementById(id);
const sections = ["loading", "summary", "payForm", "paidUp", "processing", "ceased", "lostLink"];

function show(...visible) {
    for (const id of sections) el(id).hidden = !visible.includes(id);
}

function say(text, isError) {
    el("message").textContent = text || "";
    el("message").className = isError ? "error" : "";
}

function dollars(cents) {
    return "$" + (cents / 100).toFixed(2);
}

function renderSummary(data) {
    document.title = data.societyName + " — membership payment";
    el("society").textContent = data.societyName;
    el("displayName").textContent = data.displayName;
    el("periodName").textContent = data.periodName;
    el("typeName").textContent = data.typeName;
    el("due").textContent = dollars(data.dueCents);
    el("paid").textContent = dollars(data.paidCents);
    el("balance").textContent = dollars(data.balanceCents);
}

function renderState(data) {
    renderSummary(data);
    if (data.status === "CEASED") {
        show("summary", "ceased");
    } else if (data.balanceCents <= 0) {
        el("paidUpText").textContent = "Thank you — this membership is paid up and you are "
            + "financial for " + data.periodName + ".";
        show("summary", "paidUp");
    } else {
        // LAPSED still shows the form: paying reactivates (CR-003 recompute)
        el("journalRow").hidden = data.journalPriceCents == null;
        if (data.journalPriceCents != null) {
            el("journalPrice").textContent = dollars(data.journalPriceCents);
        }
        show("summary", "payForm");
    }
}

async function fetchState() {
    const response = await fetch(API + "/pay/" + encodeURIComponent(token));
    if (response.status === 404) return null;
    if (!response.ok) throw new Error("HTTP " + response.status);
    return response.json();
}

async function startCheckout() {
    say("");
    const donationText = el("donation").value.trim();
    const donationCents = donationText ? Math.round(parseFloat(donationText) * 100) : 0;
    if (!(donationCents >= 0)) {
        say("The donation must be a positive amount.", true);
        return;
    }
    el("payNow").disabled = true;
    try {
        const response = await fetch(API + "/pay/" + encodeURIComponent(token) + "/checkout", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ journal: el("journal").checked, donationCents: donationCents }),
        });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) {
            say(data.error || ("Could not start the payment (HTTP " + response.status + ")."), true);
            el("payNow").disabled = false;
            return;
        }
        location.href = data.url; // the hosted Checkout page — our only Stripe contact
    } catch (e) {
        say("Could not reach the server — please try again.", true);
        el("payNow").disabled = false;
    }
}

/* Back from Stripe's success_url: the webhook usually lands within seconds,
 * so poll briefly before falling back to "you'll receive a receipt". */
async function pollAfterPayment() {
    show("processing");
    let data = null;
    for (let attempt = 0; attempt < 8; attempt++) {
        try {
            data = await fetchState();
            if (data && data.balanceCents <= 0 && data.status !== "CEASED") {
                el("paidUpText").textContent = "Payment received — thank you! You are financial for "
                    + data.periodName + ".";
                renderSummary(data);
                show("summary", "paidUp");
                return;
            }
        } catch (e) { /* transient — keep polling */ }
        await new Promise((resolve) => setTimeout(resolve, 1500));
    }
    if (data) {
        renderSummary(data);
        show("summary", "processing");
    } else {
        show("processing");
    }
    el("processing").firstElementChild.textContent =
        "Payment received — still processing. You'll receive a receipt by email shortly.";
}

async function boot() {
    if (!token) {
        show("lostLink");
        return;
    }
    let data;
    try {
        data = await fetchState();
    } catch (e) {
        say("Could not reach the server — please try again later.", true);
        show("loading");
        return;
    }
    if (data === null) {
        show("lostLink");
        return;
    }
    if (returnedFromStripe && data.balanceCents > 0 && data.status !== "CEASED") {
        renderSummary(data);
        await pollAfterPayment();
        return;
    }
    renderState(data);
}

el("payNow").onclick = startCheckout;

el("lostForm").onsubmit = async (event) => {
    event.preventDefault();
    try {
        await fetch(API + "/pay/lost-link", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ email: el("lostEmail").value.trim() }),
        });
    } catch (e) { /* the confirmation below is deliberately identical either way */ }
    el("lostSent").hidden = false;
};

boot();
