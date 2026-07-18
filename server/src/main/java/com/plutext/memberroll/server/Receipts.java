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

package com.plutext.memberroll.server;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.jdbi.v3.core.Handle;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The one payment-receipt renderer (CR-012). Every path — the admin "Receipt…"
 * dialog, its email button, and CR-004's Stripe webhook — composes from the
 * RECORDED payment row, never from request-time inputs, so the counter copy,
 * the emailed copy and the webhook's copy are the same document. The payment id
 * is the receipt number: payments are insert-only and corrections are negative
 * payments (Reverse, never edit), so the numbering is stable and audit-friendly.
 *
 * A negative payment renders as a "Refund record" — paper evidence of a refund
 * the treasurer made in the Stripe dashboard or by cheque. This is deliberately
 * a plain payment receipt, NOT a DGR-deductible donation receipt (out of scope),
 * so its wording cannot be mistaken for one.
 *
 * Rendering reads the CURRENT membership status (the "you are financial for
 * <period>" line follows ACTIVE now), matching what the treasurer sees: a
 * later reversal that demotes a membership is reflected the next time its
 * receipt is opened.
 */
final class Receipts {

    private Receipts() {}

    record Line(String label, int amountCents) {}

    /**
     * A composed receipt. {@code financialPeriods} lists the period name of each
     * MEMBERSHIP-allocated membership that is ACTIVE right now — one "you are
     * financial for …" line each.
     */
    record Receipt(long paymentId, boolean refund, LocalDate receivedDate, String method,
                   String bankReference, String recordedBy, List<Line> lines, int totalCents,
                   List<String> financialPeriods, String societyName) {

        /** The canonical plain-text rendering — printed, emailed and shown, all identical. */
        String text() {
            StringBuilder sb = new StringBuilder();
            sb.append(societyName).append('\n');
            sb.append(refund ? "Refund record #" : "Receipt #").append(paymentId).append('\n');
            sb.append("Received: ").append(receivedDate).append('\n');
            sb.append("Method: ").append(method);
            if (bankReference != null && !bankReference.isBlank()) {
                sb.append(" (ref ").append(bankReference).append(')');
            }
            sb.append('\n');
            sb.append("Recorded by: ").append(recordedBy).append("\n\n");
            for (Line l : lines) {
                sb.append(l.label()).append(": ").append(money(l.amountCents())).append('\n');
            }
            sb.append("Total: ").append(money(totalCents)).append('\n');
            for (String period : financialPeriods) {
                sb.append("\nYour membership is now active — you are financial for ")
                        .append(period).append(".\n");
            }
            sb.append('\n').append(societyName).append('\n');
            return sb.toString();
        }
    }

    /**
     * Compose the receipt for an already-fetched payment. Pure over the row plus
     * a per-MEMBERSHIP lookup for the period/type label and current status.
     */
    static Receipt render(Handle handle, PaymentStore.Payment p) {
        boolean refund = p.amountCents() < 0;
        List<Line> lines = new ArrayList<>();
        List<String> financialPeriods = new ArrayList<>();
        for (PaymentStore.Allocation a : p.allocations()) {
            String label;
            switch (a.type()) {
                case "MEMBERSHIP" -> {
                    MembershipStore.Detail d = a.membershipId() == null ? null
                            : MembershipStore.get(handle, a.membershipId()).orElse(null);
                    if (d != null) {
                        label = "Membership " + d.periodName() + " (" + d.typeName() + ")";
                        if ("ACTIVE".equals(d.status())) financialPeriods.add(d.periodName());
                    } else {
                        label = "Membership";
                    }
                }
                case "JOURNAL" -> label = "Journal add-on";
                case "DONATION" -> label = "Donation";
                default -> label = "Other";
            }
            lines.add(new Line(label, a.amountCents()));
        }
        return new Receipt(p.id(), refund, p.receivedDate(), p.method(), p.bankReference(),
                p.recordedBy(), lines, p.amountCents(), financialPeriods, Mail.societyName());
    }

    /**
     * The default receipt address: the payer's primary email when the payment
     * names a payer with one; else the CR-005/CR-006-attributed address of the
     * household behind the payment's first MEMBERSHIP allocation (current
     * MEMBER-relationship people, primary contact wins, else lowest person id);
     * else empty (the admin must supply an explicit {@code to}).
     */
    static Optional<String> defaultRecipient(Handle handle, PaymentStore.Payment p) {
        if (p.payerPersonId() != null) {
            Optional<String> payerEmail = handle.createQuery(
                    "SELECT e.email FROM email_address e WHERE e.person_id = :pid"
                    + " ORDER BY e.is_primary DESC, e.email_id LIMIT 1")
                    .bind("pid", p.payerPersonId()).mapTo(String.class).findOne();
            if (payerEmail.isPresent()) return payerEmail;
        }
        Long householdId = handle.createQuery(
                "SELECT m.household_id FROM payment_allocation pa"
                + " JOIN membership m ON m.membership_id = pa.membership_id"
                + " WHERE pa.payment_id = :pid AND pa.allocation_type = 'MEMBERSHIP'"
                + " ORDER BY pa.payment_allocation_id LIMIT 1")
                .bind("pid", p.id()).mapTo(Long.class).findOne().orElse(null);
        if (householdId == null) return Optional.empty();
        // primary contact (only counted when they are a MEMBER) wins, then the
        // lowest person id; is_primary/email_id order picks each person's primary
        return handle.createQuery(
                "SELECT e.email FROM household_person hp"
                + " JOIN household h ON h.household_id = hp.household_id"
                + " JOIN email_address e ON e.person_id = hp.person_id"
                + " WHERE hp.household_id = :hh AND hp.left_household_date IS NULL"
                + "   AND hp.relationship_type = 'MEMBER'"
                + " ORDER BY (hp.person_id = h.primary_contact_person_id) DESC, hp.person_id,"
                + "          e.is_primary DESC, e.email_id LIMIT 1")
                .bind("hh", householdId).mapTo(String.class).findOne();
    }

    /** The email subject line for a receipt send (per-tenant branded). */
    static String subject(Receipt r) {
        return r.societyName() + (r.refund() ? " — refund record" : " — payment receipt");
    }

    /** The receipt as JSON: the structured header/line/total fields, the canonical text, and the default address. */
    static JsonObject toJson(Receipt r, String defaultTo) {
        JsonArrayBuilder lines = Json.createArrayBuilder();
        for (Line l : r.lines()) {
            lines.add(Json.createObjectBuilder().add("label", l.label()).add("amountCents", l.amountCents()));
        }
        JsonArrayBuilder financial = Json.createArrayBuilder();
        for (String period : r.financialPeriods()) financial.add(period);
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("paymentId", r.paymentId())
                .add("refund", r.refund())
                .add("receivedDate", r.receivedDate().toString())
                .add("method", r.method());
        AdminPeopleResource.addNullable(b, "bankReference", r.bankReference());
        b.add("recordedBy", r.recordedBy())
                .add("societyName", r.societyName())
                .add("lines", lines)
                .add("totalCents", r.totalCents())
                .add("financialPeriods", financial)
                // lets the dialog disable Email upfront, mirroring the CR-005 mail banner
                .add("mailEnabled", Mail.enabled())
                .add("text", r.text());
        AdminPeopleResource.addNullable(b, "defaultTo", defaultTo);
        return b.build();
    }

    /** Money with a leading sign for refunds, reusing CR-004's canonical dollar format for the magnitude. */
    static String money(int cents) {
        return cents < 0 ? "-" + PayResource.dollars(-cents) : PayResource.dollars(cents);
    }
}
