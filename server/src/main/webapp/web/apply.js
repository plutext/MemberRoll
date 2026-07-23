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

/* The public application form (CR-007). Classic script, no auth.js — this
 * page never logs in (the pay.html pattern). Two modes from the query
 * string: plain visit = the form (or "closed" while the committee hasn't
 * enabled it); ?confirm=<token> = the email round trip's landing, which
 * POSTs the token and shows the outcome. */
"use strict";

const API = "../api";
const params = new URLSearchParams(location.search);
const confirmToken = params.get("confirm");

const el = (id) => document.getElementById(id);
const sections = ["loading", "closed", "applyForm", "submitted", "confirming", "confirmed", "badLink"];

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

let types = [];

function selectedType() {
    return types.find((t) => String(t.id) === el("fType").value);
}

/* Mirror the server's people-count rule (the CR-010 pattern): a max-1 type
 * can still carry a partner, so the second-person fields stay usable — only
 * the "also applying" choice is closed off. */
function reflectType() {
    const type = selectedType();
    if (!type) return;
    el("typePrice").textContent = "Annual subscription: " + dollars(type.priceCents);
    const memberOption = el("fRel2").querySelector('option[value="MEMBER"]');
    const maxOne = type.maximumPeople === 1;
    memberOption.disabled = maxOne;
    if (maxOne && el("fRel2").value === "MEMBER") el("fRel2").value = "PARTNER";
}

async function boot() {
    if (confirmToken) {
        show("confirming");
        try {
            const response = await fetch(API + "/apply/confirm", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ token: confirmToken }),
            });
            show(response.ok ? "confirmed" : "badLink");
        } catch (e) {
            say("Could not reach the server — please try again later.", true);
            show("confirming");
        }
        return;
    }
    let data;
    try {
        const response = await fetch(API + "/apply/options");
        if (!response.ok) throw new Error("HTTP " + response.status);
        data = await response.json();
    } catch (e) {
        say("Could not reach the server — please try again later.", true);
        show("loading");
        return;
    }
    document.title = data.societyName + " — membership application";
    el("society").textContent = data.societyName + " — membership application";
    if (!data.open) {
        show("closed");
        return;
    }
    types = data.types;
    el("fType").innerHTML = "";
    for (const t of types) {
        const o = document.createElement("option");
        o.value = t.id;
        o.textContent = t.name + " — " + dollars(t.priceCents) + "/year";
        el("fType").appendChild(o);
    }
    el("fType").onchange = reflectType;
    reflectType();
    show("applyForm");
}

el("form").onsubmit = async (event) => {
    event.preventDefault();
    say("");
    const body = {
        membershipTypeId: Number(el("fType").value),
        givenName: el("fGiven").value.trim(),
        familyName: el("fFamily").value.trim(),
        email: el("fEmail").value.trim(),
        phone: el("fPhone").value.trim() || null,
        addressLine1: el("fLine1").value.trim() || null,
        addressLine2: el("fLine2").value.trim() || null,
        locality: el("fLocality").value.trim() || null,
        state: el("fState").value.trim() || null,
        postcode: el("fPostcode").value.trim() || null,
        message: el("fMessage").value.trim() || null,
        website: el("hpWebsite").value || null, // honeypot rides along untouched
    };
    const given2 = el("fGiven2").value.trim();
    const family2 = el("fFamily2").value.trim();
    if (given2 || family2) {
        body.secondPerson = {
            givenName: given2,
            familyName: family2,
            email: el("fEmail2").value.trim() || null,
            relationship: el("fRel2").value,
        };
    }
    el("submitBtn").disabled = true;
    try {
        const response = await fetch(API + "/apply", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
        });
        if (response.status === 202) {
            show("submitted");
            return;
        }
        const data = await response.json().catch(() => ({}));
        say(data.error || ("Could not submit the application (HTTP " + response.status + ")."), true);
    } catch (e) {
        say("Could not reach the server — please try again.", true);
    } finally {
        el("submitBtn").disabled = false;
    }
};

boot();
