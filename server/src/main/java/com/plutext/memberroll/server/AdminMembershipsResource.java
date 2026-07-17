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

import jakarta.annotation.security.RolesAllowed;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jdbi.v3.core.Jdbi;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Individual memberships (CR-003), admin-only. Creation snapshots the fee due
 * and copies the household's current composition; ACTIVE is never hand-set
 * (it follows from payments — see AdminPaymentsResource), so the only status
 * transitions here are lapse/undo-lapse and cease. A type change re-snapshots
 * the amount due but is refused once any payment has been allocated (repricing
 * under money is register drift).
 */
@Path("admin/memberships")
@RolesAllowed("admin")
public class AdminMembershipsResource {

    private static final Set<String> CESSATION_REASONS = Set.of("RESIGNED", "DECEASED", "OTHER");

    private final Jdbi jdbi = Db.jdbi();
    private final MembershipStore memberships = new MembershipStore(jdbi);
    private final PaymentStore payments = new PaymentStore(jdbi);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(String body) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        try {
            long householdId = Payloads.reqLong(request, "householdId");
            long periodId = Payloads.reqLong(request, "membershipPeriodId");
            long typeId = Payloads.reqLong(request, "membershipTypeId");
            LocalDate startOverride = Payloads.optDate(request, "startDate");
            MembershipStore.CreateOutcome outcome = jdbi.inTransaction(handle ->
                    memberships.createForHousehold(handle, householdId, periodId, typeId, startOverride));
            JsonObject detail = detailJson(outcome.membershipId());
            JsonObjectBuilder b = Json.createObjectBuilder(detail);
            if (outcome.lateJoiningWarning()) {
                b.add("warning", "today is past the period's late-joining cutoff — consider the next period");
            }
            return Response.status(Response.Status.CREATED).entity(b.build().toString()).build();
        } catch (ConflictException e) {
            return conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("id") long id) {
        if (memberships.get(id).isEmpty()) return notFound();
        return Response.ok(detailJson(id).toString()).build();
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") long id, String body) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        try {
            Response outcome = jdbi.inTransaction(handle -> {
                if (!memberships.exists(handle, id)) return notFound();
                Long typeId = Payloads.optLong(request, "membershipTypeId");
                String status = Payloads.optString(request, "status");
                if (typeId != null) {
                    memberships.changeType(handle, id, typeId);
                } else if (status != null) {
                    Response transition = applyStatus(handle, id, status, request);
                    if (transition != null) return transition; // a 400 the transaction rolls back
                } else {
                    return badRequest("specify membershipTypeId or status");
                }
                return null; // success — fall through to the fresh detail below
            });
            return outcome != null ? outcome : Response.ok(detailJson(id).toString()).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    private Response applyStatus(org.jdbi.v3.core.Handle handle, long id, String status, JsonObject request) {
        switch (status) {
            case "CEASED" -> {
                LocalDate ceasedDate = Payloads.reqDate(request, "ceasedDate");
                String reason = Payloads.optString(request, "cessationReason");
                if (reason == null || !CESSATION_REASONS.contains(reason)) {
                    return badRequest("cessationReason must be one of " + CESSATION_REASONS);
                }
                memberships.cease(handle, id, ceasedDate, reason);
            }
            case "LAPSED" -> {
                if (!memberships.lapse(handle, id)) {
                    return badRequest("only a PENDING_PAYMENT membership can be lapsed");
                }
            }
            case "PENDING_PAYMENT" -> {
                if (!memberships.unlapse(handle, id)) {
                    return badRequest("only a LAPSED membership can be returned to PENDING_PAYMENT");
                }
            }
            default -> {
                return badRequest("status must be one of CEASED, LAPSED, PENDING_PAYMENT");
            }
        }
        return null;
    }

    // ---- JSON ---------------------------------------------------------------

    /** Full membership detail: fields + people + payments + derived paid. */
    private JsonObject detailJson(long id) {
        MembershipStore.Detail d = memberships.get(id).orElseThrow();
        List<PaymentStore.Payment> paid = payments.listForMembership(id);
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("id", d.id())
                .add("membershipPeriodId", d.periodId())
                .add("periodName", d.periodName())
                .add("membershipTypeId", d.typeId())
                .add("typeName", d.typeName())
                .add("householdId", d.householdId());
        AdminPeopleResource.addNullable(b, "householdName", d.householdName());
        b.add("status", d.status());
        AdminPeopleResource.addNullable(b, "applicationDate", str(d.applicationDate()));
        AdminPeopleResource.addNullable(b, "approvedDate", str(d.approvedDate()));
        b.add("startDate", d.startDate().toString());
        b.add("endDate", d.endDate().toString());
        b.add("amountDueCents", d.amountDueCents());
        b.add("amountPaidCents", d.amountPaidCents());
        AdminPeopleResource.addNullable(b, "ceasedDate", str(d.ceasedDate()));
        AdminPeopleResource.addNullable(b, "cessationReason", d.cessationReason());
        JsonArrayBuilder people = Json.createArrayBuilder();
        for (MembershipStore.Person p : d.people()) {
            people.add(Json.createObjectBuilder()
                    .add("personId", p.personId())
                    .add("givenName", p.givenName())
                    .add("familyName", p.familyName())
                    .add("role", p.role() == null ? "" : p.role())
                    .add("statutory", p.statutory())
                    .add("voting", p.voting())
                    .add("committee", p.committee()));
        }
        b.add("people", people);
        JsonArrayBuilder paymentsJson = Json.createArrayBuilder();
        for (PaymentStore.Payment p : paid) paymentsJson.add(AdminPaymentsResource.paymentJson(p));
        b.add("payments", paymentsJson);
        return b.build();
    }

    private static String str(LocalDate d) {
        return d == null ? null : d.toString();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Json.createObjectBuilder().add("error", message).build().toString()).build();
    }

    private static Response conflict(String message) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Json.createObjectBuilder().add("error", message).build().toString()).build();
    }

    private static Response notFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Json.createObjectBuilder().add("error", "no such membership").build().toString()).build();
    }
}
