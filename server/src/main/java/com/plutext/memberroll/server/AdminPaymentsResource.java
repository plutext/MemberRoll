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
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import org.jdbi.v3.core.Jdbi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Manual payment entry (CR-003), admin-only. Payments are insert-only: a
 * mistake is corrected by an equal-and-opposite (negative) payment, never an
 * edit or delete — hence no PUT/DELETE here. Recording a payment recomputes
 * every membership it touches in the same transaction, so ACTIVE always
 * follows from allocations covering the fee (schema rule 6). Positive STRIPE
 * payments only ever arrive via CR-004's webhook; the one hand-entered STRIPE
 * shape is a NEGATIVE amount — recording a refund made in the Stripe
 * dashboard (refunds move money there, never here).
 */
@Path("admin/payments")
@RolesAllowed("admin")
public class AdminPaymentsResource {

    private static final Set<String> METHODS = Set.of("CASH", "CHEQUE", "BANK_TRANSFER", "STRIPE", "OTHER");
    private static final Set<String> ALLOCATION_TYPES = Set.of("MEMBERSHIP", "JOURNAL", "DONATION", "OTHER");

    private final Jdbi jdbi = Db.jdbi();
    private final PaymentStore payments = new PaymentStore(jdbi);
    private final MembershipStore memberships = new MembershipStore(jdbi);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(String body, @Context SecurityContext security) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        String recordedBy = security.getUserPrincipal() instanceof UserPrincipal user
                ? user.getUsername() : "admin";
        try {
            LocalDate receivedDate = Payloads.reqDate(request, "receivedDate");
            int amountCents = Payloads.reqInt(request, "amountCents");
            if (amountCents == 0) return badRequest("amountCents must be non-zero");
            String method = Payloads.optString(request, "method");
            if (method == null || !METHODS.contains(method)) {
                return badRequest("method must be one of " + METHODS);
            }
            Long payerPersonId = Payloads.optLong(request, "payerPersonId");
            String bankReference = Payloads.optString(request, "bankReference");
            String notes = Payloads.optString(request, "notes");
            List<PaymentStore.AllocationInput> allocations = parseAllocations(request);
            // every LINE must be negative, not just the total — a negative-total
            // STRIPE entry must not smuggle in a positive MEMBERSHIP allocation
            // that never came through the webhook
            if ("STRIPE".equals(method)
                    && (amountCents > 0 || allocations.stream().anyMatch(a -> a.amountCents() > 0))) {
                return badRequest("positive STRIPE amounts arrive via the webhook only;"
                        + " hand-entered STRIPE must be all-negative (recording a dashboard refund)");
            }

            JsonObject result = jdbi.inTransaction(handle -> {
                // membership FKs must resolve to a clean 400, not a raw SQL error
                for (PaymentStore.AllocationInput a : allocations) {
                    if (a.membershipId() != null && !memberships.exists(handle, a.membershipId())) {
                        throw new IllegalArgumentException("no such membership #" + a.membershipId());
                    }
                }
                PaymentStore.InsertResult inserted = payments.insert(handle, receivedDate, amountCents,
                        method, payerPersonId, bankReference, null, notes, recordedBy, allocations);
                for (long membershipId : inserted.touchedMembershipIds()) {
                    memberships.recompute(handle, membershipId, receivedDate);
                }
                // read the fresh state (uncommitted, same handle) for the response
                JsonArrayBuilder touched = Json.createArrayBuilder();
                JsonArrayBuilder warnings = Json.createArrayBuilder();
                for (long membershipId : inserted.touchedMembershipIds()) {
                    MembershipStore.Detail d = MembershipStore.get(handle, membershipId).orElseThrow();
                    touched.add(Json.createObjectBuilder().add("id", d.id()).add("status", d.status())
                            .add("amountDueCents", d.amountDueCents())
                            .add("amountPaidCents", d.amountPaidCents()));
                    if (d.amountPaidCents() > d.amountDueCents()) {
                        warnings.add("membership #" + d.id() + " is overpaid (paid "
                                + d.amountPaidCents() + " > due " + d.amountDueCents() + ")");
                    }
                }
                return Json.createObjectBuilder().add("id", inserted.paymentId())
                        .add("memberships", touched).add("warnings", warnings).build();
            });
            return Response.status(Response.Status.CREATED).entity(result.toString()).build();
        } catch (ConflictException e) {
            return conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("membershipId") Long membershipId,
                         @QueryParam("householdId") Long householdId,
                         @QueryParam("periodId") Long periodId,
                         @QueryParam("limit") Integer limit,
                         @QueryParam("offset") Integer offset) {
        int lim = Math.max(1, Math.min(limit == null ? 50 : limit, 500));
        int off = Math.max(0, offset == null ? 0 : offset);
        PaymentStore.Page page = payments.list(membershipId, householdId, periodId, lim, off);
        JsonArrayBuilder rows = Json.createArrayBuilder();
        for (PaymentStore.Payment p : page.payments()) rows.add(paymentJson(p));
        return Response.ok(Json.createObjectBuilder()
                .add("payments", rows).add("total", page.total()).build().toString()).build();
    }

    // ---- helpers ------------------------------------------------------------

    private List<PaymentStore.AllocationInput> parseAllocations(JsonObject request) {
        JsonArray raw = request.containsKey("allocations") && !request.isNull("allocations")
                ? request.getJsonArray("allocations") : null;
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("at least one allocation is required");
        }
        List<PaymentStore.AllocationInput> allocations = new ArrayList<>();
        for (JsonValue v : raw) {
            JsonObject a = v.asJsonObject();
            String type = Payloads.optString(a, "type");
            if (type == null || !ALLOCATION_TYPES.contains(type)) {
                throw new IllegalArgumentException("allocation type must be one of " + ALLOCATION_TYPES);
            }
            allocations.add(new PaymentStore.AllocationInput(type,
                    Payloads.optLong(a, "membershipId"), Payloads.reqInt(a, "amountCents")));
        }
        return allocations;
    }

    static JsonObject paymentJson(PaymentStore.Payment p) {
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("id", p.id())
                .add("receivedDate", p.receivedDate().toString())
                .add("amountCents", p.amountCents())
                .add("method", p.method());
        if (p.payerPersonId() == null) b.addNull("payerPersonId"); else b.add("payerPersonId", p.payerPersonId());
        AdminPeopleResource.addNullable(b, "bankReference", p.bankReference());
        b.add("recordedBy", p.recordedBy()).add("recordedAt", p.recordedAt());
        AdminPeopleResource.addNullable(b, "notes", p.notes());
        JsonArrayBuilder allocations = Json.createArrayBuilder();
        for (PaymentStore.Allocation a : p.allocations()) {
            JsonObjectBuilder ab = Json.createObjectBuilder().add("id", a.id()).add("type", a.type());
            if (a.membershipId() == null) ab.addNull("membershipId"); else ab.add("membershipId", a.membershipId());
            AdminPeopleResource.addNullable(ab, "householdName", a.householdName());
            allocations.add(ab.add("amountCents", a.amountCents()));
        }
        return b.add("allocations", allocations).build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Json.createObjectBuilder().add("error", message).build().toString()).build();
    }

    private static Response conflict(String message) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Json.createObjectBuilder().add("error", message).build().toString()).build();
    }
}
