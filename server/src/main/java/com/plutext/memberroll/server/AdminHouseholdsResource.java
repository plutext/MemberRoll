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
import jakarta.json.JsonReader;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.StringReader;
import java.util.Set;

/**
 * The register's households section (CR-001) — the billing unit.
 * Membership composition is history-preserving: DELETE on a member sets
 * left_household_date rather than removing the row, and the primary
 * contact cannot be removed (reassign first). Households are never
 * deleted; they close via status when that arrives with CR-003.
 */
@Path("admin/households")
@RolesAllowed("admin")
public class AdminHouseholdsResource {

    private static final Set<String> RELATIONSHIP_TYPES =
            Set.of("MEMBER", "PARTNER", "DEPENDANT", "OTHER");

    private final HouseholdStore store = new HouseholdStore(Db.jdbi());

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("q") String q,
                         @QueryParam("limit") @DefaultValue("50") int limit,
                         @QueryParam("offset") @DefaultValue("0") int offset) {
        HouseholdStore.Page page = store.search(q,
                Math.max(1, Math.min(limit, 200)), Math.max(0, offset));
        JsonArrayBuilder households = Json.createArrayBuilder();
        for (HouseholdStore.Summary summary : page.households()) {
            JsonObjectBuilder b = Json.createObjectBuilder().add("id", summary.id());
            AdminPeopleResource.addNullable(b, "householdName", summary.name());
            households.add(b
                    .add("primaryContactPersonId", summary.primaryContactPersonId())
                    .add("primaryContactName", summary.primaryContactName())
                    .add("status", summary.status())
                    .add("currentMembers", summary.currentMembers()));
        }
        return Response.ok(Json.createObjectBuilder()
                .add("households", households)
                .add("total", page.total())
                .build().toString()).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(String body) {
        JsonObject request = readObject(body);
        if (request == null || !request.containsKey("primaryContactPersonId")
                || request.isNull("primaryContactPersonId")) {
            return badRequest("primaryContactPersonId is required");
        }
        return store.create(optName(request), request.getJsonNumber("primaryContactPersonId").longValue())
                .map(h -> Response.status(Response.Status.CREATED).entity(toJson(h).toString()).build())
                .orElseGet(() -> badRequest("no such person for primaryContactPersonId"));
    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("id") long id) {
        return store.get(id)
                .map(h -> Response.ok(toJson(h).toString()).build())
                .orElseGet(AdminHouseholdsResource::notFound);
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") long id, String body) {
        JsonObject request = readObject(body);
        if (request == null || !request.containsKey("primaryContactPersonId")
                || request.isNull("primaryContactPersonId")) {
            return badRequest("primaryContactPersonId is required");
        }
        try {
            return store.update(id, optName(request),
                            request.getJsonNumber("primaryContactPersonId").longValue())
                    .map(h -> Response.ok(toJson(h).toString()).build())
                    .orElseGet(AdminHouseholdsResource::notFound);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("{id}/people")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response addPerson(@PathParam("id") long id, String body) {
        JsonObject request = readObject(body);
        if (request == null || !request.containsKey("personId") || request.isNull("personId")) {
            return badRequest("personId is required");
        }
        String type = request.getString("relationshipType", "MEMBER");
        if (!RELATIONSHIP_TYPES.contains(type)) {
            return badRequest("relationshipType must be one of " + RELATIONSHIP_TYPES);
        }
        return switch (store.addPerson(id, request.getJsonNumber("personId").longValue(), type)) {
            case ADDED -> store.get(id)
                    .map(h -> Response.status(Response.Status.CREATED).entity(toJson(h).toString()).build())
                    .orElseGet(AdminHouseholdsResource::notFound);
            case HOUSEHOLD_NOT_FOUND -> notFound();
            case PERSON_NOT_FOUND -> badRequest("no such person");
            case ALREADY_MEMBER -> Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\":\"already a current member\"}").build();
        };
    }

    @DELETE
    @Path("{id}/people/{personId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response removePerson(@PathParam("id") long id, @PathParam("personId") long personId) {
        return switch (store.removePerson(id, personId)) {
            case REMOVED -> store.get(id)
                    .map(h -> Response.ok(toJson(h).toString()).build())
                    .orElseGet(AdminHouseholdsResource::notFound);
            case NOT_A_CURRENT_MEMBER -> Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"not a current member\"}").build();
            case IS_PRIMARY_CONTACT -> badRequest("reassign the primary contact before removing them");
        };
    }

    // ---- helpers ---------------------------------------------------------

    private static JsonObject readObject(String body) {
        try (JsonReader reader = Json.createReader(new StringReader(body == null ? "" : body))) {
            return reader.readObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String optName(JsonObject request) {
        String name = request.getString("householdName", "").trim();
        return name.isEmpty() ? null : name;
    }

    private static JsonObject toJson(HouseholdStore.Household household) {
        JsonObjectBuilder b = Json.createObjectBuilder().add("id", household.id());
        AdminPeopleResource.addNullable(b, "householdName", household.name());
        b.add("primaryContactPersonId", household.primaryContactPersonId())
                .add("status", household.status());
        JsonArrayBuilder members = Json.createArrayBuilder();
        for (HouseholdStore.Member member : household.members()) {
            JsonObjectBuilder m = Json.createObjectBuilder()
                    .add("personId", member.personId())
                    .add("givenName", member.givenName())
                    .add("familyName", member.familyName())
                    .add("relationshipType", member.relationshipType())
                    .add("joinedDate", member.joinedDate().toString());
            AdminPeopleResource.addNullable(m, "leftDate",
                    member.leftDate() == null ? null : member.leftDate().toString());
            members.add(m);
        }
        return b.add("members", members).build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"" + message + "\"}").build();
    }

    private static Response notFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"no such household\"}").build();
    }
}
