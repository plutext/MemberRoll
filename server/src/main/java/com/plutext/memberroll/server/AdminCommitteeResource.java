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
import jakarta.json.JsonValue;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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
import java.util.Locale;
import java.util.Optional;

/**
 * The committee register (CR-013), admin-only. The primary flow is the AGM
 * roll: every seat is vacated and re-filled each AGM (constitution cl. 15-16),
 * so {@code POST /agm} closes all open appointments and inserts the new slate
 * in one transaction — the CR-010 atomic discipline, every rejection thrown
 * (400/409) inside the transaction lambda so a bad line rolls the whole roll
 * back. Mid-year changes ride the single-appointment endpoints. Corrections
 * are edits (PUT) / removals (DELETE), never reversals — administrative
 * reference data, not a financial ledger.
 *
 * {@code GET /contacts} is the seam CR-007 consumes: current committee members'
 * routing addresses, and the current secretary specifically.
 */
@Path("admin/committee")
@RolesAllowed("admin")
public class AdminCommitteeResource {

    private final Jdbi jdbi = Db.jdbi();
    private final CommitteeStore committee = new CommitteeStore(jdbi);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("includeEnded") boolean includeEnded) {
        JsonArrayBuilder out = Json.createArrayBuilder();
        for (CommitteeStore.Appointment a : committee.list(includeEnded)) out.add(appointmentJson(a));
        return Response.ok(Json.createObjectBuilder().add("committee", out).build().toString()).build();
    }

    /** CR-007 seam: current committee routing addresses + the current secretary. */
    @GET
    @Path("contacts")
    @Produces(MediaType.APPLICATION_JSON)
    public Response contacts() {
        JsonArrayBuilder members = Json.createArrayBuilder();
        JsonObject secretary = null;
        for (CommitteeStore.Contact c : committee.contacts()) {
            JsonObject row = contactJson(c);
            members.add(row);
            if ("SECRETARY".equals(c.office()) && secretary == null) secretary = row;
        }
        JsonObjectBuilder b = Json.createObjectBuilder().add("members", members);
        if (secretary == null) b.addNull("secretary"); else b.add("secretary", secretary);
        return Response.ok(b.build().toString()).build();
    }

    @POST
    @Path("agm")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response agmRoll(String body, @Context SecurityContext security) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        String recordedBy = recordedBy(security);
        LocalDate agmDate;
        String minuteRef;
        List<CommitteeStore.SlateLine> slate = new ArrayList<>();
        try {
            agmDate = Payloads.reqDate(request, "agmDate");
            minuteRef = Payloads.optString(request, "minuteRef");
            if (!request.containsKey("appointments") || request.isNull("appointments")) {
                throw new IllegalArgumentException("appointments is required (may be empty)");
            }
            for (JsonValue v : request.getJsonArray("appointments")) {
                JsonObject line = v.asJsonObject();
                slate.add(new CommitteeStore.SlateLine(Payloads.reqLong(line, "personId"),
                        office(line), Payloads.optString(line, "notes")));
            }
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        try {
            List<String> warnings = jdbi.inTransaction(handle ->
                    committee.agmRoll(handle, agmDate, minuteRef, slate, recordedBy));
            return Response.status(Response.Status.CREATED)
                    .entity(committeeWithWarnings(warnings).toString()).build();
        } catch (ConflictException e) {
            return conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("appointments")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response openAppointment(String body, @Context SecurityContext security) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        String recordedBy = recordedBy(security);
        long personId;
        String office;
        LocalDate startedDate;
        LocalDate electedDate;
        String minuteRef;
        String notes;
        try {
            personId = Payloads.reqLong(request, "personId");
            office = office(request);
            startedDate = Payloads.reqDate(request, "startedDate");
            electedDate = Payloads.optDate(request, "electedDate");
            minuteRef = Payloads.optString(request, "minuteRef");
            notes = Payloads.optString(request, "notes");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        try {
            List<String> warnings = new ArrayList<>();
            CommitteeStore.Appointment created = jdbi.inTransaction(handle ->
                    committee.open(handle, personId, office, startedDate, electedDate,
                            minuteRef, notes, recordedBy, warnings));
            JsonArrayBuilder w = Json.createArrayBuilder();
            for (String s : warnings) w.add(s);
            return Response.status(Response.Status.CREATED).entity(Json.createObjectBuilder()
                    .add("appointment", appointmentJson(created)).add("warnings", w)
                    .build().toString()).build();
        } catch (ConflictException e) {
            return conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PUT
    @Path("appointments/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateAppointment(@PathParam("id") long id, String body) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        try {
            Optional<CommitteeStore.Appointment> updated = jdbi.inTransaction(handle -> {
                Optional<CommitteeStore.Appointment> existing = committee.find(handle, id);
                if (existing.isEmpty()) return Optional.empty();
                CommitteeStore.Appointment e = existing.get();
                // partial update — a key absent from the body keeps the stored value.
                // ended_date is nullable-settable, so {endedDate:null} would re-open a
                // term: the CR's flow only ever sets it (a resignation), so treat an
                // absent key as "keep" and a present one as the new value.
                String office = request.containsKey("office") ? office(request) : e.office();
                LocalDate started = request.containsKey("startedDate")
                        ? Payloads.reqDate(request, "startedDate") : e.startedDate();
                LocalDate ended = request.containsKey("endedDate")
                        ? Payloads.optDate(request, "endedDate") : e.endedDate();
                LocalDate elected = request.containsKey("electedDate")
                        ? Payloads.optDate(request, "electedDate") : e.electedDate();
                String minuteRef = request.containsKey("minuteRef")
                        ? Payloads.optString(request, "minuteRef") : e.minuteRef();
                String notes = request.containsKey("notes")
                        ? Payloads.optString(request, "notes") : e.notes();
                return committee.update(handle, id, office, started, ended, elected, minuteRef, notes);
            });
            return updated.map(a -> Response.ok(appointmentJson(a).toString()).build())
                    .orElseGet(AdminCommitteeResource::notFound);
        } catch (ConflictException ex) {
            return conflict(ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
    }

    @DELETE
    @Path("appointments/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteAppointment(@PathParam("id") long id) {
        boolean removed = jdbi.inTransaction(handle -> committee.delete(handle, id));
        if (!removed) return notFound();
        return Response.ok(Json.createObjectBuilder().add("deleted", id).build().toString()).build();
    }

    // ---- parsing / JSON -----------------------------------------------------

    private static String office(JsonObject o) {
        String office = Payloads.optString(o, "office");
        if (office == null) throw new IllegalArgumentException("office is required");
        office = office.toUpperCase(Locale.ROOT);
        if (!CommitteeStore.OFFICES.contains(office)) {
            throw new IllegalArgumentException("office must be one of " + CommitteeStore.OFFICES);
        }
        return office;
    }

    private static String recordedBy(SecurityContext security) {
        return security.getUserPrincipal() instanceof UserPrincipal user ? user.getUsername() : "admin";
    }

    private JsonObject committeeWithWarnings(List<String> warnings) {
        JsonArrayBuilder out = Json.createArrayBuilder();
        for (CommitteeStore.Appointment a : committee.list(false)) out.add(appointmentJson(a));
        JsonArrayBuilder w = Json.createArrayBuilder();
        for (String s : warnings) w.add(s);
        return Json.createObjectBuilder().add("committee", out).add("warnings", w).build();
    }

    private static JsonObject appointmentJson(CommitteeStore.Appointment a) {
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("id", a.id()).add("personId", a.personId())
                .add("personName", a.personName()).add("office", a.office())
                .add("startedDate", a.startedDate().toString());
        AdminPeopleResource.addNullable(b, "endedDate", a.endedDate() == null ? null : a.endedDate().toString());
        AdminPeopleResource.addNullable(b, "electedDate", a.electedDate() == null ? null : a.electedDate().toString());
        AdminPeopleResource.addNullable(b, "minuteRef", a.minuteRef());
        AdminPeopleResource.addNullable(b, "notes", a.notes());
        AdminPeopleResource.addNullable(b, "recordedBy", a.recordedBy());
        return b.build();
    }

    private static JsonObject contactJson(CommitteeStore.Contact c) {
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("personId", c.personId()).add("personName", c.personName())
                .add("office", c.office());
        AdminPeopleResource.addNullable(b, "email", c.email());
        return b.build();
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
                .entity(Json.createObjectBuilder().add("error", "no such appointment").build().toString()).build();
    }
}
