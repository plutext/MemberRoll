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
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Membership periods and the operations that run a membership year (CR-003),
 * admin-only: create/reprice a period, roll the prior year's ACTIVE
 * households forward (preview then apply, like the CR-002 import), bulk-lapse
 * the unpaid, the treasurer's financial-status view, and the CSV exports.
 * Period creation is deliberately separate from rollover — the prices are
 * typed in at the moment they are confirmed with the society, so a rollover
 * can never invent a fee.
 */
@Path("admin/periods")
@RolesAllowed("admin")
public class AdminPeriodsResource {

    private final Jdbi jdbi = Db.jdbi();
    private final PeriodStore periods = new PeriodStore(jdbi);
    private final MembershipStore memberships = new MembershipStore(jdbi);

    // ---- periods ------------------------------------------------------------

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        JsonArrayBuilder out = Json.createArrayBuilder();
        for (PeriodStore.Period p : periods.list()) out.add(periodJson(p));
        return Response.ok(Json.createObjectBuilder().add("periods", out).build().toString()).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(String body) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        try {
            String name = Payloads.optString(request, "name");
            if (name == null) return badRequest("name is required");
            LocalDate start = Payloads.reqDate(request, "startDate");
            LocalDate end = Payloads.reqDate(request, "endDate");
            LocalDate renewalOpen = Payloads.optDate(request, "renewalOpenDate");
            LocalDate cutoff = Payloads.optDate(request, "lateJoiningCutoff");
            Integer journalPrice = parseJournalPrice(request);
            Map<String, Integer> prices = parsePrices(request);
            PeriodStore.Period created = jdbi.inTransaction(handle ->
                    periods.create(handle, name, start, end, renewalOpen, cutoff, journalPrice, prices));
            return Response.status(Response.Status.CREATED).entity(periodJson(created).toString()).build();
        } catch (ConflictException e) {
            return conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") long id, String body,
                           @QueryParam("repriceUnpaid") boolean repriceUnpaid) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        try {
            Map<String, Integer> prices = request.containsKey("prices")
                    ? parsePrices(request) : Map.of();
            Optional<PeriodStore.Period> updated = jdbi.inTransaction(handle -> {
                Optional<PeriodStore.Period> existing = PeriodStore.get(handle, id);
                if (existing.isEmpty()) return Optional.empty();
                PeriodStore.Period e = existing.get();
                LocalDate start = presentDate(request, "startDate") ? Payloads.reqDate(request, "startDate") : e.startDate();
                LocalDate end = presentDate(request, "endDate") ? Payloads.reqDate(request, "endDate") : e.endDate();
                LocalDate renewalOpen = request.containsKey("renewalOpenDate")
                        ? Payloads.optDate(request, "renewalOpenDate") : e.renewalOpenDate();
                LocalDate cutoff = request.containsKey("lateJoiningCutoff")
                        ? Payloads.optDate(request, "lateJoiningCutoff") : e.lateJoiningCutoff();
                Integer journalPrice = request.containsKey("journalPriceCents")
                        ? parseJournalPrice(request) : e.journalPriceCents();
                return periods.update(handle, id, start, end, renewalOpen, cutoff, journalPrice,
                        prices, repriceUnpaid);
            });
            return updated.map(p -> Response.ok(periodJson(p).toString()).build()).orElseGet(this::notFound);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    // ---- rollover -----------------------------------------------------------

    @POST
    @Path("{id}/rollover/preview")
    @Produces(MediaType.APPLICATION_JSON)
    public Response rolloverPreview(@PathParam("id") long id, @QueryParam("from") Long from) {
        if (periods.get(id).isEmpty()) return notFound();
        return Response.ok(rolloverJson(memberships.rolloverPreview(id, from)).toString()).build();
    }

    @POST
    @Path("{id}/rollover")
    @Produces(MediaType.APPLICATION_JSON)
    public Response rolloverApply(@PathParam("id") long id, @QueryParam("from") Long from) {
        if (periods.get(id).isEmpty()) return notFound();
        MembershipStore.RolloverReport report =
                jdbi.inTransaction(handle -> memberships.rolloverApply(handle, id, from));
        return Response.ok(rolloverJson(report).toString()).build();
    }

    @POST
    @Path("{id}/lapse-unpaid")
    @Produces(MediaType.APPLICATION_JSON)
    public Response lapseUnpaid(@PathParam("id") long id) {
        if (periods.get(id).isEmpty()) return notFound();
        int count = jdbi.inTransaction(handle -> memberships.lapseUnpaid(handle, id));
        return Response.ok(Json.createObjectBuilder().add("lapsed", count).build().toString()).build();
    }

    // ---- financial status view ----------------------------------------------

    @GET
    @Path("{id}/memberships")
    @Produces(MediaType.APPLICATION_JSON)
    public Response statusView(@PathParam("id") long id,
                               @QueryParam("status") String status,
                               @QueryParam("type") String type,
                               @QueryParam("q") String q,
                               @QueryParam("limit") Integer limit,
                               @QueryParam("offset") Integer offset) {
        if (periods.get(id).isEmpty()) return notFound();
        int lim = Math.max(1, Math.min(limit == null ? 200 : limit, 1000));
        int off = Math.max(0, offset == null ? 0 : offset);
        MembershipStore.StatusPage page = memberships.statusPage(id,
                blankToNull(status), blankToNull(type), q, lim, off);
        JsonArrayBuilder rows = Json.createArrayBuilder();
        for (MembershipStore.StatusRow r : page.rows()) rows.add(statusRowJson(r));
        return Response.ok(Json.createObjectBuilder()
                .add("rows", rows).add("total", page.total())
                .add("summary", summaryJson(page.summary())).build().toString()).build();
    }

    // ---- CSV exports --------------------------------------------------------

    @GET
    @Path("{id}/export/agm-register.csv")
    @Produces("text/csv")
    public Response exportAgm(@PathParam("id") long id) {
        if (periods.get(id).isEmpty()) return notFound();
        StringWriter sw = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            csv.printRecord("Family name", "Given name", "Household", "Membership type");
            for (MembershipStore.AgmRow r : memberships.agmRegister(id)) {
                csv.printRecord(r.familyName(), r.givenName(), r.household(), r.type());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return csvResponse(sw.toString(), "agm-register.csv");
    }

    @GET
    @Path("{id}/export/mailing-labels.csv")
    @Produces("text/csv")
    public Response exportMailingLabels(@PathParam("id") long id) {
        if (periods.get(id).isEmpty()) return notFound();
        StringWriter sw = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            csv.printRecord("Name", "Line 1", "Line 2", "Locality", "State", "Postcode");
            for (MembershipStore.MailingLabel r : memberships.mailingLabels(id)) {
                csv.printRecord(r.name(), r.line1(), r.line2(), r.locality(), r.state(), r.postcode());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return csvResponse(sw.toString(), "mailing-labels.csv");
    }

    @GET
    @Path("{id}/export/financial.csv")
    @Produces("text/csv")
    public Response exportFinancial(@PathParam("id") long id) {
        if (periods.get(id).isEmpty()) return notFound();
        StringWriter sw = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            csv.printRecord("Household", "Contact", "Type", "Status", "Due (cents)", "Paid (cents)");
            for (MembershipStore.StatusRow r : memberships.allStatusRows(id)) {
                csv.printRecord(r.householdName(), r.primaryContactName(), r.typeName(),
                        r.status(), r.amountDueCents(), r.amountPaidCents());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return csvResponse(sw.toString(), "financial.csv");
    }

    // ---- parsing ------------------------------------------------------------

    private static Map<String, Integer> parsePrices(JsonObject request) {
        JsonArray raw = request.containsKey("prices") && !request.isNull("prices")
                ? request.getJsonArray("prices") : null;
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("prices are required (one per membership type)");
        }
        Map<String, Integer> prices = new LinkedHashMap<>();
        for (JsonValue v : raw) {
            JsonObject line = v.asJsonObject();
            String type = Payloads.optString(line, "type");
            if (type == null) throw new IllegalArgumentException("each price needs a type");
            prices.put(type.toUpperCase(java.util.Locale.ROOT), Payloads.reqInt(line, "amountCents"));
        }
        return prices;
    }

    /** null (absent or JSON null) = journal add-on not offered. */
    private static Integer parseJournalPrice(JsonObject request) {
        Integer cents = Payloads.optInt(request, "journalPriceCents");
        if (cents != null && cents < 0) throw new IllegalArgumentException("journalPriceCents must be >= 0");
        return cents;
    }

    private static boolean presentDate(JsonObject o, String key) {
        return o.containsKey(key) && !o.isNull(key);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ---- JSON ---------------------------------------------------------------

    private static JsonObject periodJson(PeriodStore.Period p) {
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("id", p.id()).add("name", p.name())
                .add("startDate", p.startDate().toString())
                .add("endDate", p.endDate().toString());
        AdminPeopleResource.addNullable(b, "renewalOpenDate",
                p.renewalOpenDate() == null ? null : p.renewalOpenDate().toString());
        AdminPeopleResource.addNullable(b, "lateJoiningCutoff",
                p.lateJoiningCutoff() == null ? null : p.lateJoiningCutoff().toString());
        AdminPeopleResource.addNullable(b, "journalPriceCents", p.journalPriceCents());
        JsonArrayBuilder prices = Json.createArrayBuilder();
        for (PeriodStore.Price price : p.prices()) {
            JsonObjectBuilder pb = Json.createObjectBuilder()
                    .add("typeId", price.typeId()).add("type", price.typeName())
                    .add("amountCents", price.amountCents());
            AdminPeopleResource.addNullable(pb, "minimumPeople", price.minimumPeople());
            AdminPeopleResource.addNullable(pb, "maximumPeople", price.maximumPeople());
            prices.add(pb);
        }
        b.add("prices", prices);
        b.add("countsByStatus", countsJson(p.countsByStatus()));
        return b.build();
    }

    private static JsonObject rolloverJson(MembershipStore.RolloverReport r) {
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("targetPeriodId", r.targetPeriodId())
                .add("targetPeriodName", r.targetPeriodName() == null ? "" : r.targetPeriodName());
        if (r.fromPeriodId() == null) b.addNull("fromPeriodId"); else b.add("fromPeriodId", r.fromPeriodId());
        AdminPeopleResource.addNullable(b, "fromPeriodName", r.fromPeriodName());
        b.add("toCreate", r.toCreate());
        JsonArrayBuilder skipped = Json.createArrayBuilder();
        for (MembershipStore.Skip s : r.skipped()) {
            skipped.add(Json.createObjectBuilder().add("householdId", s.householdId()).add("reason", s.reason()));
        }
        b.add("skipped", skipped);
        JsonArrayBuilder errors = Json.createArrayBuilder();
        for (String e : r.errors()) errors.add(e);
        b.add("errors", errors);
        if (r.created() == null) b.addNull("created"); else b.add("created", r.created());
        return b.build();
    }

    private static JsonObject statusRowJson(MembershipStore.StatusRow r) {
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("membershipId", r.membershipId())
                .add("householdId", r.householdId());
        AdminPeopleResource.addNullable(b, "householdName", r.householdName());
        b.add("primaryContactName", r.primaryContactName());
        JsonArrayBuilder names = Json.createArrayBuilder();
        for (String n : r.memberNames()) names.add(n);
        b.add("memberNames", names);
        return b.add("typeName", r.typeName()).add("status", r.status())
                .add("amountDueCents", r.amountDueCents())
                .add("amountPaidCents", r.amountPaidCents()).build();
    }

    private static JsonObject summaryJson(MembershipStore.Summary s) {
        return Json.createObjectBuilder()
                .add("countsByStatus", countsJson(s.countsByStatus()))
                .add("totalDueCents", s.totalDueCents())
                .add("totalCollectedCents", s.totalCollectedCents()).build();
    }

    private static JsonObject countsJson(Map<String, Integer> counts) {
        JsonObjectBuilder b = Json.createObjectBuilder();
        counts.forEach(b::add);
        return b.build();
    }

    private static Response csvResponse(String csv, String filename) {
        return Response.ok(csv).type("text/csv")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"").build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Json.createObjectBuilder().add("error", message).build().toString()).build();
    }

    private static Response conflict(String message) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Json.createObjectBuilder().add("error", message).build().toString()).build();
    }

    private Response notFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Json.createObjectBuilder().add("error", "no such period").build().toString()).build();
    }
}
