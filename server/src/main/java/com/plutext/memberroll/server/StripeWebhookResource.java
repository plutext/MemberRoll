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

import com.stripe.net.Webhook;

import jakarta.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jdbi.v3.core.Jdbi;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stripe's callback (CR-004) — the part that must be boringly correct. The
 * signature over the raw bytes is the endpoint's ENTIRE authentication
 * (guest-reachable otherwise). Only checkout.session.completed with
 * payment_status=paid records anything; the payment insert and the status
 * recompute share one transaction (the CR-003 pattern). Idempotency is
 * schema rule 12 doing its job: a redelivered event hits the partial unique
 * index on external_transaction_id and is answered 200, no-op — no separate
 * processed-events table. A verified-but-unprocessable event is logged
 * loudly and ALSO answered 200: Stripe retries non-2xx for days, and a
 * retry cannot fix a malformed event. The receipt email rides after the
 * commit, best-effort — its failure never fails the webhook.
 *
 * The event body is parsed with jakarta.json rather than stripe-java's model
 * classes: the handful of fields needed here beats coupling deserialization
 * to the SDK's pinned API version.
 */
@Path("stripe/webhook")
public class StripeWebhookResource {

    private static final Logger LOG = Logger.getLogger(StripeWebhookResource.class.getName());

    private final Jdbi jdbi = Db.jdbi();
    private final PaymentStore payments = new PaymentStore(jdbi);
    private final MembershipStore memberships = new MembershipStore(jdbi);

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response receive(byte[] rawBody, @HeaderParam("Stripe-Signature") String signature) {
        String secret = Mail.env("STRIPE_WEBHOOK_SECRET");
        if (secret == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(json("error", "webhook not configured")).build();
        }
        // the signature is over the exact bytes; re-encoding is only identical
        // because invalid UTF-8 would fail the check anyway (and should)
        String payload = new String(rawBody == null ? new byte[0] : rawBody, StandardCharsets.UTF_8);
        try {
            Webhook.Signature.verifyHeader(payload, signature, secret, Webhook.DEFAULT_TOLERANCE);
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(json("error", "signature verification failed")).build();
        }

        JsonObject event = Payloads.read(payload);
        if (event == null) return ok("ignored"); // verified but not a JSON object — nothing to do
        if (!"checkout.session.completed".equals(event.getString("type", null))) return ok("ignored");
        JsonObject data = objectOrNull(event, "data");
        JsonObject session = data == null ? null : objectOrNull(data, "object");
        if (session == null || !"paid".equals(session.getString("payment_status", null))) {
            return ok("ignored");
        }

        try {
            return record(event, session);
        } catch (UnprocessableEvent e) {
            LOG.severe("UNPROCESSABLE Stripe event " + event.getString("id", "?") + ": "
                    + e.getMessage() + " — payment NOT recorded, reconcile via the Stripe dashboard");
            return ok("unprocessable");
        }
    }

    private static final class UnprocessableEvent extends RuntimeException {
        UnprocessableEvent(String message) { super(message); }
    }

    private Response record(JsonObject event, JsonObject session) {
        JsonObject metadata = objectOrNull(session, "metadata");
        if (metadata == null) throw new UnprocessableEvent("session has no metadata");
        // every money key is required — this server authors every session's
        // metadata, so a missing key means truncation, not an optional field
        // (silently zeroing it would misallocate real money); tokenId alone
        // is bookkeeping and must never block recording a payment
        long membershipId = metaLong(metadata, "membershipId");
        Long tokenId = metadata.containsKey("tokenId") ? metaLong(metadata, "tokenId") : null;
        int membershipCents = (int) metaLong(metadata, "membershipCents");
        int journalCents = (int) metaLong(metadata, "journalCents");
        int donationCents = (int) metaLong(metadata, "donationCents");
        JsonNumber amountNumber = numberOrNull(session, "amount_total");
        if (amountNumber == null) throw new UnprocessableEvent("session has no amount_total");
        int amountTotal = amountNumber.intValueExact();
        // zero can't be a payment (and would trip the amount_cents <> 0 CHECK);
        // Stripe never signs a negative session total
        if (amountTotal <= 0) throw new UnprocessableEvent("non-positive amount_total " + amountTotal);
        String sessionId = session.getString("id", null);
        if (sessionId == null) throw new UnprocessableEvent("session has no id");
        // the PaymentIntent id is the durable transaction reference; the session
        // id only if Stripe ever omits it (it identifies the payment equally)
        String externalId = session.getString("payment_intent", null) != null
                ? session.getString("payment_intent") : sessionId;
        JsonNumber created = numberOrNull(event, "created");
        LocalDate receivedDate = created == null ? LocalDate.now(ZoneId.systemDefault())
                : Instant.ofEpochSecond(created.longValue())
                        .atZone(ZoneId.systemDefault()).toLocalDate();

        List<PaymentStore.AllocationInput> allocations = new ArrayList<>();
        if (membershipCents != 0) {
            allocations.add(new PaymentStore.AllocationInput("MEMBERSHIP", membershipId, membershipCents));
        }
        // JOURNAL carries the membership id so "already bought" is detectable
        if (journalCents != 0) {
            allocations.add(new PaymentStore.AllocationInput("JOURNAL", membershipId, journalCents));
        }
        if (donationCents != 0) {
            allocations.add(new PaymentStore.AllocationInput("DONATION", null, donationCents));
        }
        StringBuilder notesText = new StringBuilder("Stripe Checkout ").append(sessionId);
        int diff = amountTotal - (membershipCents + journalCents + donationCents);
        if (diff != 0) {
            // should be impossible (we authored the session) — never silently drop money
            allocations.add(new PaymentStore.AllocationInput("OTHER", null, diff));
            notesText.append(" — AMOUNT MISMATCH: session total ").append(amountTotal)
                    .append(" != metadata sum ").append(amountTotal - diff)
                    .append("; difference recorded as OTHER");
            LOG.severe("Stripe amount mismatch on " + sessionId + ": " + notesText);
        }
        String notes = notesText.toString();
        if (allocations.isEmpty()) throw new UnprocessableEvent("event allocates no money");

        long paymentId;
        try {
            paymentId = jdbi.inTransaction(handle -> {
                if (!memberships.exists(handle, membershipId)) {
                    throw new UnprocessableEvent("no such membership #" + membershipId);
                }
                PaymentStore.InsertResult inserted = payments.insert(handle, receivedDate, amountTotal,
                        "STRIPE", null, null, externalId, notes, "stripe-webhook", allocations);
                for (long touched : inserted.touchedMembershipIds()) {
                    memberships.recompute(handle, touched, receivedDate);
                }
                if (tokenId != null) RenewalTokenStore.markUsed(handle, tokenId);
                return inserted.paymentId();
            });
        } catch (RuntimeException e) {
            if (e instanceof UnprocessableEvent u) throw u;
            if (isDuplicateExternalId(e)) {
                LOG.info("Stripe event redelivery for " + externalId + " — already recorded, no-op");
                return ok("duplicate");
            }
            if (e instanceof IllegalArgumentException) throw new UnprocessableEvent(e.getMessage());
            throw e; // a real failure (e.g. database down) — non-2xx, Stripe retries
        }

        sendReceipt(session, paymentId);
        return ok("recorded");
    }

    /**
     * The receipt rides after the commit, best-effort (its failure never fails
     * the webhook). Composed by the shared CR-012 renderer from the RECORDED
     * payment — only the recipient is Stripe-specific (the Checkout email the
     * payer typed, which the register never holds for an online payment).
     */
    private void sendReceipt(JsonObject session, long paymentId) {
        try {
            JsonObject customer = session.getJsonObject("customer_details");
            String email = customer == null ? null : customer.getString("email", null);
            if (email == null) return;
            Receipts.Receipt receipt = jdbi.withHandle(handle ->
                    PaymentStore.find(handle, paymentId).map(p -> Receipts.render(handle, p)).orElse(null));
            if (receipt == null) return;
            // async: SMTP must not delay the 200 past Stripe's webhook timeout
            Mail.sendAsync(email, Receipts.subject(receipt), receipt.text());
        } catch (Exception e) {
            // the payment is recorded; the receipt is best-effort
            LOG.log(Level.WARNING, "receipt email failed for payment #" + paymentId, e);
        }
    }

    // ---- helpers ------------------------------------------------------------

    /** get a nested object, treating absent, JSON null, and wrong-typed alike. */
    private static JsonObject objectOrNull(JsonObject o, String key) {
        return o.get(key) instanceof JsonObject nested ? nested : null;
    }

    /** get a number, treating absent, JSON null, and wrong-typed alike. */
    private static JsonNumber numberOrNull(JsonObject o, String key) {
        return o.get(key) instanceof JsonNumber n ? n : null;
    }

    /** Stripe metadata values are strings; a missing/garbled one is unprocessable, not a 500. */
    private static long metaLong(JsonObject metadata, String key) {
        String value = metadata.getString(key, null);
        if (value == null) throw new UnprocessableEvent("metadata missing " + key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new UnprocessableEvent("metadata " + key + " is not a number: " + value);
        }
    }

    /**
     * Rule 12: a redelivery trips the partial unique index payment_external_txn.
     * SQLSTATE 23505 alone is decisive here — payment_external_txn is the only
     * unique constraint this transaction can violate — and matching the
     * constraint NAME in the message would break under Postgres message
     * localization or driver rewrapping.
     */
    private static boolean isDuplicateExternalId(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "23505".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private static Response ok(String outcome) {
        return Response.ok(json("outcome", outcome)).build();
    }

    private static String json(String key, String value) {
        return Json.createObjectBuilder().add(key, value).build().toString();
    }
}
