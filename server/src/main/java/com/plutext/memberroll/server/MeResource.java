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
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The caller's own role claim, and — since CR-006 — their "my membership"
 * view. The claim endpoint is the path for accounts that predate the
 * registration picker (the webapp prompts them) and for changing one's
 * claim later; setting a claim resets the verified flag — the admin
 * verified the old claim, not the new one.
 *
 * The membership endpoints carry NO {@code @RolesAllowed} on purpose: the
 * {@code person.keycloak_subject} link is the authority, not the
 * self-claimed {@code member} role (CR-006's role-model decision). Any
 * authenticated account may ask and learns only about itself —
 * {@code {linked: false}} reveals nothing about others, and there is
 * deliberately no lookup by email (an enumeration oracle). Guests get the
 * usual 401 challenge.
 */
@Path("me")
public class MeResource {

    @PUT
    @Path("claim")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response claim(@Context SecurityContext security, String body) {
        if (!(security.getUserPrincipal() instanceof UserPrincipal user)) {
            throw new NotAuthorizedException("Bearer realm=\"memberroll\"");
        }
        String role;
        try (JsonReader reader = Json.createReader(new StringReader(body == null ? "" : body))) {
            JsonObject request = reader.readObject();
            JsonValue value = request.getOrDefault("role", JsonValue.NULL);
            role = value.getValueType() == JsonValue.ValueType.NULL
                    ? null : request.getString("role");
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"body must be {\\\"role\\\": \\\"<role>\\\"} or {\\\"role\\\": null}\"}")
                    .build();
        }
        if (role != null && !KeycloakAdmin.CLAIMABLE.contains(role)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"not a claimable role: " + WhoAmIResource.escape(role)
                            + " (claimable: " + String.join(", ",
                                    KeycloakAdmin.CLAIMABLE.stream().sorted().toList()) + ")\"}")
                    .build();
        }
        KeycloakAdmin keycloak = KeycloakAdmin.instance();
        try {
            keycloak.updateAttributes(user.getName(),
                    java.util.Collections.singletonMap(KeycloakAdmin.CLAIM_ATTRIBUTE, role));
            boolean changed = keycloak.syncClaim(user.getName(), role);
            if (role == null) {
                // clearing the claim always clears verification too
                keycloak.updateAttributes(user.getName(),
                        Map.of(KeycloakAdmin.VERIFIED_ATTRIBUTE, "false"));
            }
            Set<String> claimable = new LinkedHashSet<>(keycloak.realmRoles(user.getName()));
            claimable.retainAll(KeycloakAdmin.CLAIMABLE);
            return Response.ok("{"
                    + (role == null ? "\"claimed_role\":null" :
                            "\"claimed_role\":\"" + WhoAmIResource.escape(role) + "\"")
                    + ",\"granted\":" + jsonArray(claimable)
                    + ",\"changed\":" + changed
                    + ",\"note\":\"takes effect in your next token (refresh or re-login)\"}")
                    .build();
        } catch (IOException e) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"error\":\"Keycloak admin call failed: "
                            + WhoAmIResource.escape(String.valueOf(e.getMessage())) + "\"}")
                    .build();
        }
    }

    // ---- my membership (CR-006) -----------------------------------------

    @GET
    @Path("membership")
    @Produces(MediaType.APPLICATION_JSON)
    public Response membership(@Context SecurityContext security) {
        UserPrincipal user = requireUser(security);
        org.jdbi.v3.core.Jdbi jdbi = Db.jdbi();
        return jdbi.withHandle(handle -> {
            SelfServeStore.LinkedPerson person =
                    SelfServeStore.personBySubject(handle, user.getName()).orElse(null);
            JsonObjectBuilder out = Json.createObjectBuilder()
                    .add("societyName", Mail.societyName());
            if (person == null) {
                return Response.ok(out.add("linked", false).build().toString()).build();
            }
            out.add("linked", true)
                    .add("person", Json.createObjectBuilder()
                            .add("givenName", person.givenName())
                            .add("familyName", person.familyName()));
            JsonArrayBuilder memberships = Json.createArrayBuilder();
            for (SelfServeStore.MembershipRow m : SelfServeStore.currentMemberships(handle, person.personId())) {
                memberships.add(Json.createObjectBuilder()
                        .add("membershipId", m.membershipId())
                        .add("displayName", m.displayName())
                        .add("periodName", m.periodName())
                        .add("typeName", m.typeName())
                        .add("status", m.status())
                        .add("amountDueCents", m.amountDueCents())
                        .add("amountPaidCents", m.amountPaidCents())
                        .add("paid", m.amountPaidCents() >= m.amountDueCents()));
            }
            JsonArrayBuilder history = Json.createArrayBuilder();
            for (SelfServeStore.HistoryRow h : SelfServeStore.history(handle, person.personId())) {
                history.add(Json.createObjectBuilder()
                        .add("periodName", h.periodName())
                        .add("typeName", h.typeName())
                        .add("status", h.status()));
            }
            return Response.ok(out.add("memberships", memberships)
                    .add("history", history).build().toString()).build();
        });
    }

    /**
     * Pay-now is a handoff, not a second payment path: mint a CR-004 magic
     * link (fresh per call) and let the browser navigate to the one Stripe
     * surface. 404 unless the membership is in the caller's payable set —
     * indistinguishable from "no such membership", so nothing enumerates.
     */
    @POST
    @Path("membership/{id}/pay-link")
    @Produces(MediaType.APPLICATION_JSON)
    public Response payLink(@Context SecurityContext security, @PathParam("id") long membershipId) {
        UserPrincipal user = requireUser(security);
        org.jdbi.v3.core.Jdbi jdbi = Db.jdbi();
        return jdbi.inTransaction(handle -> {
            Long personId = SelfServeStore.personBySubject(handle, user.getName())
                    .map(SelfServeStore.LinkedPerson::personId).orElse(null);
            if (personId == null || !SelfServeStore.canPay(handle, personId, membershipId)) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"no such membership\"}").build();
            }
            RenewalTokenStore.Minted minted =
                    RenewalTokenStore.mint(handle, membershipId).orElseThrow();
            return Response.ok(Json.createObjectBuilder()
                    .add("url", PayResource.payUrl(minted.token()))
                    .build().toString()).build();
        });
    }

    // ---- my membership card (CR-017) ------------------------------------

    /**
     * The card PNG for one of the caller's ACTIVE memberships. 404 unless the
     * caller is a current MEMBER-relationship member of the membership's
     * household AND that membership is ACTIVE ({@link Cards#compose} is the
     * gate) — indistinguishable from "no such membership", so nothing
     * enumerates. {@code no-store}: a card asserts current standing and must
     * not be cached past a status change.
     */
    @GET
    @Path("membership/{id}/card")
    @Produces("image/png")
    public Response card(@Context SecurityContext security, @PathParam("id") long membershipId) {
        UserPrincipal user = requireUser(security);
        return Db.jdbi().withHandle(handle -> {
            Cards.Card card = cardFor(handle, user, membershipId);
            if (card == null) return cardNotFound();
            return Response.ok(Cards.png(card), "image/png")
                    .header("Cache-Control", "no-store").build();
        });
    }

    /**
     * The card's composed fields + {@code mailEnabled} + {@code emailTo} (the
     * caller's primary register email, null when none) — the page decides
     * which buttons to show from this. Same 404 gate as the PNG.
     */
    @GET
    @Path("membership/{id}/card/info")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cardInfo(@Context SecurityContext security, @PathParam("id") long membershipId) {
        UserPrincipal user = requireUser(security);
        return Db.jdbi().withHandle(handle -> {
            Cards.Card card = cardFor(handle, user, membershipId);
            if (card == null) return cardNotFound();
            String emailTo = Cards.primaryEmail(handle, card.personId()).orElse(null);
            JsonObjectBuilder b = Cards.toJson(card, Mail.enabled());
            AdminPeopleResource.addNullable(b, "emailTo", emailTo);
            return Response.ok(b.build().toString()).build();
        });
    }

    /**
     * Email the card to the caller's OWN primary register email — deliberately
     * no {@code to} parameter (a member's card goes to the member's address;
     * the server is not an arbitrary-destination mailer). 400 when the person
     * has no email; 503 when mail is not configured (the checkout mirror,
     * never a silent no-op). Stateless — nothing is written.
     */
    @POST
    @Path("membership/{id}/card/email")
    @Produces(MediaType.APPLICATION_JSON)
    public Response emailCard(@Context SecurityContext security, @PathParam("id") long membershipId) {
        UserPrincipal user = requireUser(security);
        return Db.jdbi().withHandle(handle -> {
            Cards.Card card = cardFor(handle, user, membershipId);
            if (card == null) return cardNotFound();
            if (!Mail.enabled()) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity("{\"error\":\"email is not configured on this server"
                                + " — download or print your card instead\"}").build();
            }
            String to = Cards.primaryEmail(handle, card.personId()).orElse(null);
            if (to == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"no email on file for your record"
                                + " — download or print your card instead\"}").build();
            }
            // async: SMTP must never hold a Tomcat thread past the response
            Mail.sendAsync(to, Cards.subject(card), Cards.emailBody(card), Cards.attachment(card));
            return Response.accepted(Json.createObjectBuilder()
                    .add("sentTo", to).build().toString()).build();
        });
    }

    /** The caller's card for this membership, or null (unlinked, or the gate refuses). */
    private static Cards.Card cardFor(org.jdbi.v3.core.Handle handle, UserPrincipal user, long membershipId) {
        Long personId = SelfServeStore.personBySubject(handle, user.getName())
                .map(SelfServeStore.LinkedPerson::personId).orElse(null);
        if (personId == null) return null;
        return Cards.compose(handle, membershipId, personId).orElse(null);
    }

    private static Response cardNotFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"no such membership\"}").build();
    }

    private static UserPrincipal requireUser(SecurityContext security) {
        if (!(security.getUserPrincipal() instanceof UserPrincipal user)) {
            throw new NotAuthorizedException("Bearer realm=\"memberroll\"");
        }
        return user;
    }

    private static String jsonArray(Set<String> values) {
        return "[" + values.stream().sorted()
                .map(v -> "\"" + WhoAmIResource.escape(v) + "\"")
                .reduce((a, b) -> a + "," + b).orElse("") + "]";
    }
}
