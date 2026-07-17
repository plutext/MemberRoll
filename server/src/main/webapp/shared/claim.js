/* The role-claim modal, shared so every page gates login identically
 * instead of diverging. Same shape as auth.js: a plain-script IIFE
 * global. Pages provide a #claim container (and its modal CSS) and load
 * this after ../shared/auth.js.
 *
 * Semantics (deliberately MANDATORY): a signed-in user with no claim
 * gets a blocking modal — no dismiss; the only way on is choosing.
 * "Change role" reuses the modal with a Cancel. Claiming grants the
 * role server-side; the immediate Auth.refresh() makes it live now
 * instead of at the next natural token refresh.
 */
"use strict";

const Claim = (() => {

    // the roles a user may claim for themselves (never manager/admin);
    // keep in sync with KeycloakAdmin.CLAIMABLE and the realm JSON
    const LABELS = {
        member: "Member",
        other: "Other",
    };

    /**
     * Open the modal. current = the existing claim (null → mandatory, no
     * dismiss); onClaimed(role, label) runs after a successful claim and
     * token refresh — the page updates its own identity/status UI there.
     */
    function prompt(current, onClaimed) {
        const box = document.getElementById("claim");
        box.innerHTML = "";
        box.hidden = false;
        const card = document.createElement("div");
        card.className = "card";
        const heading = document.createElement("h3");
        heading.textContent = current ? "Change your role" : "Before you continue";
        const lead = document.createElement("p");
        lead.textContent = current
            ? "Pick the new role — an admin re-verifies the new claim."
            : "Which describes you? The choice is required: it sets your role "
              + "in the app (an admin verifies it later).";
        const error = document.createElement("p");
        error.className = "error";
        card.append(heading, lead);
        for (const [role, label] of Object.entries(LABELS)) {
            const btn = document.createElement("button");
            btn.textContent = `I'm a ${label.toLowerCase()}`;
            btn.disabled = role === current;
            btn.onclick = async () => {
                const response = await Auth.api("/me/claim", {
                    method: "PUT",
                    headers: {"Content-Type": "application/json"},
                    body: JSON.stringify({role}),
                });
                if (!response) return;
                if (!response.ok) {
                    error.textContent = `Role claim failed: HTTP ${response.status} — try again.`;
                    return;
                }
                await Auth.refresh(); // pick up the newly granted role right away
                box.hidden = true;
                await onClaimed(role, label);
            };
            card.appendChild(btn);
        }
        if (current) {
            const cancel = document.createElement("button");
            cancel.textContent = "Cancel";
            cancel.onclick = () => { box.hidden = true; };
            card.appendChild(cancel);
        }
        card.appendChild(error);
        box.appendChild(card);
    }

    return {LABELS, prompt};
})();
