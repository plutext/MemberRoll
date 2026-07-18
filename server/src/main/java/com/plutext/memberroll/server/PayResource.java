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

import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The magic-link pay page's API (CR-004), guest-reachable by design: holding
 * the emailed token IS the authorisation, and login is never the payment
 * gate. Unknown and expired tokens are indistinguishable 404s, and no other
 * lookup exists, so an unauthenticated visitor is never shown membership
 * status directly — the lost-link endpoint only ever answers with the same
 * neutral 202 and emails a matching address instead. Checkout line items are
 * computed server-side (the client never sends an amount for anything but
 * the free-entry donation); card data never touches this server — the page's
 * only contact with Stripe is the redirect to the hosted Checkout URL.
 */
@Path("pay")
public class PayResource {

    private static final Logger LOG = Logger.getLogger(PayResource.class.getName());

    private final Jdbi jdbi = Db.jdbi();

    // ---- pay-page data ------------------------------------------------------

    @GET
    @Path("{token}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("token") String token) {
        return jdbi.withHandle(handle -> RenewalTokenStore.resolve(handle, token))
                .map(v -> Response.ok(viewJson(v).toString()).build())
                .orElseGet(PayResource::tokenNotFound);
    }

    // ---- checkout -----------------------------------------------------------

    @POST
    @Path("{token}/checkout")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response checkout(@PathParam("token") String token, String body) {
        String secretKey = Mail.env("STRIPE_SECRET_KEY");
        if (secretKey == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(error("online payment is not configured on this server"
                            + " — contact the society to pay another way")).build();
        }
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");

        RenewalTokenStore.PayView view = jdbi.withHandle(handle ->
                RenewalTokenStore.resolve(handle, token)).orElse(null);
        if (view == null) return tokenNotFound();
        if ("CEASED".equals(view.status())) {
            return conflict("this membership has ceased — contact the society");
        }

        boolean journal = Payloads.optBool(request, "journal", false);
        Integer donation;
        try {
            donation = Payloads.optInt(request, "donationCents");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        if (donation != null && donation < 0) return badRequest("donationCents must be >= 0");
        if (journal && (view.journalPriceCents() == null || view.journalBought())) {
            return badRequest("the journal add-on is not available for this membership");
        }

        int membershipCents = Math.max(0, view.amountDueCents() - view.amountPaidCents());
        int journalCents = journal ? view.journalPriceCents() : 0;
        int donationCents = donation == null ? 0 : donation;
        if (membershipCents + journalCents + donationCents <= 0) {
            return conflict("there is nothing to pay — this membership is already paid up");
        }

        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                // card-only keeps completion synchronous: checkout.session.completed
                // with payment_status=paid is then the only event that matters
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                .setSuccessUrl(payUrl(token) + "&paid=1")
                .setCancelUrl(payUrl(token))
                .putMetadata("membershipId", Long.toString(view.membershipId()))
                .putMetadata("tokenId", Long.toString(view.tokenId()))
                .putMetadata("membershipCents", Integer.toString(membershipCents))
                .putMetadata("journalCents", Integer.toString(journalCents))
                .putMetadata("donationCents", Integer.toString(donationCents));
        if (membershipCents > 0) {
            params.addLineItem(lineItem(membershipCents,
                    "Membership " + view.periodName() + " (" + view.typeName() + ")"));
        }
        if (journalCents > 0) params.addLineItem(lineItem(journalCents, "Journal add-on"));
        if (donationCents > 0) params.addLineItem(lineItem(donationCents, "Donation"));

        try {
            Session session = Session.create(params.build(),
                    RequestOptions.builder().setApiKey(secretKey).build());
            return Response.ok(Json.createObjectBuilder().add("url", session.getUrl())
                    .build().toString()).build();
        } catch (StripeException e) {
            LOG.log(Level.WARNING, "Stripe Checkout session creation failed", e);
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(error("could not start the payment — please try again later")).build();
        }
    }

    private static SessionCreateParams.LineItem lineItem(int cents, String name) {
        return SessionCreateParams.LineItem.builder()
                .setQuantity(1L)
                .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency("aud")
                        .setUnitAmount((long) cents)
                        .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                .setName(name).build())
                        .build())
                .build();
    }

    // ---- lost link ----------------------------------------------------------

    /**
     * Always the same 202, match or not — no enumeration. The actual lookup,
     * mint and send run on the mail thread AFTER the response, so response
     * latency reveals nothing either; a per-address cooldown keeps a scripted
     * loop from email-bombing a member or growing renewal_token unboundedly.
     * One address can match several people (couples share one in this
     * demographic): every matching person's current households' current or
     * renewal-open memberships get a link, listed once each in a single mail.
     */
    // per-address cooldown; bounded so attacker-supplied addresses can't grow it forever
    private static final ConcurrentHashMap<String, Long> LOST_LINK_RECENT = new ConcurrentHashMap<>();
    private static final long LOST_LINK_COOLDOWN_MS = 10 * 60 * 1000;

    @POST
    @Path("lost-link")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response lostLink(String body) {
        JsonObject request = Payloads.read(body);
        String email = request == null ? null : Payloads.optString(request, "email");
        if (email != null) {
            String address = email.toLowerCase(Locale.ROOT);
            if (underCooldown(address)) {
                LOG.info("lost-link: cooldown for " + address + " — not re-sending");
            } else {
                Mail.async(() -> {
                    try {
                        sendLostLink(address);
                    } catch (Exception e) {
                        // the 202 already went out; this is server-side only
                        LOG.log(Level.WARNING, "lost-link handling failed for " + address, e);
                    }
                });
            }
        }
        return Response.accepted(Json.createObjectBuilder()
                .add("message", "if that address matches a member, we've emailed the link")
                .build().toString()).build();
    }

    private static boolean underCooldown(String address) {
        long now = System.currentTimeMillis();
        if (LOST_LINK_RECENT.size() > 10_000) LOST_LINK_RECENT.clear();
        Long last = LOST_LINK_RECENT.putIfAbsent(address, now);
        if (last == null) return false;
        if (now - last < LOST_LINK_COOLDOWN_MS) return true;
        LOST_LINK_RECENT.put(address, now);
        return false;
    }

    private void sendLostLink(String email) {
        record Line(String displayName, String periodName, int balanceCents, String url) {}
        List<Line> lines = jdbi.inTransaction(handle -> {
            List<Line> out = new ArrayList<>();
            for (RenewalTokenStore.LostLinkRow row : RenewalTokenStore.lostLinkRows(handle, email)) {
                RenewalTokenStore.Minted minted =
                        RenewalTokenStore.mint(handle, row.membershipId()).orElseThrow();
                out.add(new Line(row.displayName(), row.periodName(),
                        Math.max(0, row.amountDueCents() - row.amountPaidCents()), payUrl(minted.token())));
            }
            return out;
        });
        if (lines.isEmpty()) {
            LOG.info("lost-link: no current membership matches " + email + " — not sending");
            return;
        }
        StringBuilder text = new StringBuilder("Hello,\n\nHere ")
                .append(lines.size() == 1 ? "is the payment link" : "are the payment links")
                .append(" you asked for:\n");
        for (Line l : lines) {
            text.append('\n').append(l.displayName()).append(" — ").append(l.periodName());
            text.append(l.balanceCents() > 0
                    ? "\nBalance due: " + dollars(l.balanceCents()) : "\nPaid up — thank you!");
            text.append("\nPay or check your membership: ").append(l.url()).append('\n');
        }
        text.append("\nIf you did not request this, you can ignore this email.\n\n")
                .append(Mail.societyName()).append('\n');
        Mail.send(email, Mail.societyName() + " — your membership payment link", text.toString());
    }

    // ---- shared helpers -----------------------------------------------------

    /** The public pay-page URL carrying a raw token (also used by the admin mint + CR-005). */
    static String payUrl(String token) {
        return publicBaseUrl() + "/web/pay.html?t=" + token;
    }

    private static volatile boolean warnedNoBaseUrl;

    static String publicBaseUrl() {
        String base = Mail.env("PUBLIC_BASE_URL");
        if (base == null) {
            // the dev default is a production foot-gun: emailed links would
            // point at localhost and fail only in members' inboxes — warn loudly
            if (!warnedNoBaseUrl) {
                warnedNoBaseUrl = true;
                LOG.warning("PUBLIC_BASE_URL is not set — pay links will use"
                        + " http://localhost:18080/server (correct only for local dev)");
            }
            base = "http://localhost:18080/server";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    static String dollars(int cents) {
        return String.format(Locale.ROOT, "$%d.%02d", cents / 100, Math.abs(cents % 100));
    }

    private static JsonObject viewJson(RenewalTokenStore.PayView v) {
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("societyName", Mail.societyName())
                .add("displayName", v.displayName())
                .add("periodName", v.periodName())
                .add("typeName", v.typeName())
                .add("status", v.status())
                .add("dueCents", v.amountDueCents())
                .add("paidCents", v.amountPaidCents())
                .add("balanceCents", Math.max(0, v.amountDueCents() - v.amountPaidCents()));
        // null once bought (or never offered): the page simply hides the add-on
        AdminPeopleResource.addNullable(b, "journalPriceCents",
                v.journalBought() ? null : v.journalPriceCents());
        return b.build();
    }

    private static Response tokenNotFound() {
        // deliberately identical for unknown and expired
        return Response.status(Response.Status.NOT_FOUND)
                .entity(error("link not recognised or expired")).build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST).entity(error(message)).build();
    }

    private static Response conflict(String message) {
        return Response.status(Response.Status.CONFLICT).entity(error(message)).build();
    }

    private static String error(String message) {
        return Json.createObjectBuilder().add("error", message).build().toString();
    }
}
