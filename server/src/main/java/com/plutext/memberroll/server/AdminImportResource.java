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
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.nio.charset.StandardCharsets;

/**
 * Member-list CSV import (CR-002), admin-only. The CSV is the request body
 * ({@code text/csv} — the admin page reads the file with FileReader and posts
 * the text, no multipart machinery). {@code preview} validates and reports but
 * writes nothing; the bare POST applies, refusing with 400 (and writing
 * nothing) if validation found any error. Both cap the body at 1 MB so a
 * society register stays a register, not an ETL feed.
 */
@Path("admin/import")
@RolesAllowed("admin")
public class AdminImportResource {

    private static final int MAX_BYTES = 1024 * 1024; // 1 MB

    private final ImportService service = new ImportService(Db.jdbi());

    @POST
    @Path("preview")
    @Consumes("text/csv")
    @Produces(MediaType.APPLICATION_JSON)
    public Response preview(@QueryParam("period") String period, String csv) {
        Response tooLarge = rejectIfTooLarge(csv);
        if (tooLarge != null) return tooLarge;
        return Response.ok(toJson(service.preview(csv, period)).toString()).build();
    }

    @POST
    @Consumes("text/csv")
    @Produces(MediaType.APPLICATION_JSON)
    public Response apply(@QueryParam("period") String period, String csv,
                          @Context SecurityContext security) {
        Response tooLarge = rejectIfTooLarge(csv);
        if (tooLarge != null) return tooLarge;
        String recordedBy = security.getUserPrincipal() instanceof UserPrincipal user
                ? user.getUsername() : "import";
        ImportService.Report report = service.apply(csv, period, recordedBy);
        // created == null signals validation errors: nothing was written
        Response.Status status = report.created() == null
                ? Response.Status.BAD_REQUEST : Response.Status.OK;
        return Response.status(status).entity(toJson(report).toString()).build();
    }

    private static Response rejectIfTooLarge(String csv) {
        if (csv != null && csv.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                    .entity("{\"error\":\"CSV exceeds 1 MB\"}").build();
        }
        return null;
    }

    // ---- report -> JSON -----------------------------------------------------

    private static jakarta.json.JsonObject toJson(ImportService.Report report) {
        JsonObjectBuilder b = Json.createObjectBuilder().add("rows", report.rows());
        JsonArrayBuilder errors = Json.createArrayBuilder();
        for (ImportService.Issue e : report.errors()) {
            errors.add(Json.createObjectBuilder().add("line", e.line()).add("message", e.message()));
        }
        b.add("errors", errors);
        JsonArrayBuilder warnings = Json.createArrayBuilder();
        for (ImportService.Issue w : report.warnings()) {
            warnings.add(Json.createObjectBuilder().add("line", w.line()).add("message", w.message()));
        }
        b.add("warnings", warnings);
        JsonArrayBuilder skipped = Json.createArrayBuilder();
        for (ImportService.Skip s : report.skipped()) {
            skipped.add(Json.createObjectBuilder().add("line", s.line()).add("reason", s.reason()));
        }
        b.add("skipped", skipped);
        b.add("toCreate", counts(report.toCreate()));
        if (report.created() != null) b.add("created", counts(report.created()));
        return b.build();
    }

    private static JsonObjectBuilder counts(ImportService.Counts c) {
        return Json.createObjectBuilder()
                .add("people", c.people())
                .add("households", c.households())
                .add("memberships", c.memberships())
                .add("payments", c.payments());
    }
}
