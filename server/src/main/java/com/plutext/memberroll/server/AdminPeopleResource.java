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
import jakarta.json.JsonValue;
import jakarta.ws.rs.Consumes;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The register's people section (CR-001). Ids are server-issued bigints
 * — unlike notes there is no caller-chosen id, so nothing here is a path
 * component to validate. Contact details (emails, phones) ride inside
 * the person payload. No DELETE on purpose: people are never deleted
 * (schema business rule 7) — departures are dates, not row removals.
 */
@Path("admin/people")
@RolesAllowed("admin")
public class AdminPeopleResource {

    private static final Set<String> PHONE_TYPES = Set.of("MOBILE", "HOME", "WORK");

    private final PersonStore store = new PersonStore(Db.jdbi());

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("q") String q,
                         @QueryParam("limit") @DefaultValue("50") int limit,
                         @QueryParam("offset") @DefaultValue("0") int offset) {
        PersonStore.Page page = store.search(q,
                Math.max(1, Math.min(limit, 200)), Math.max(0, offset));
        JsonArrayBuilder people = Json.createArrayBuilder();
        for (PersonStore.Person person : page.people()) {
            people.add(toJson(person));
        }
        return Response.ok(Json.createObjectBuilder()
                .add("people", people)
                .add("total", page.total())
                .build().toString()).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(String body) {
        PersonStore.Person draft;
        try {
            draft = parse(body);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        PersonStore.Person created = store.create(draft);
        return Response.status(Response.Status.CREATED)
                .entity(toJson(created).toString()).build();
    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("id") long id) {
        return store.get(id)
                .map(person -> Response.ok(toJson(person).toString()).build())
                .orElseGet(AdminPeopleResource::notFound);
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") long id, String body) {
        PersonStore.Person draft;
        try {
            draft = parse(body);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        return store.update(id, draft)
                .map(person -> Response.ok(toJson(person).toString()).build())
                .orElseGet(AdminPeopleResource::notFound);
    }

    // ---- payload <-> record --------------------------------------------

    /** @throws IllegalArgumentException with a caller-facing message */
    static PersonStore.Person parse(String body) {
        JsonObject o;
        try (JsonReader reader = Json.createReader(new StringReader(body == null ? "" : body))) {
            o = reader.readObject();
        } catch (Exception e) {
            throw new IllegalArgumentException("body must be a JSON object");
        }
        try {
            String given = o.getString("givenName", "").trim();
            String family = o.getString("familyName", "").trim();
            if (given.isEmpty() || family.isEmpty()) {
                throw new IllegalArgumentException("givenName and familyName are required");
            }
            List<PersonStore.Email> emails = new ArrayList<>();
            for (JsonValue v : o.getJsonArray("emails") == null
                    ? JsonValue.EMPTY_JSON_ARRAY : o.getJsonArray("emails")) {
                JsonObject e = v.asJsonObject();
                String email = e.getString("email", "").trim();
                if (email.isEmpty() || !email.contains("@")) {
                    throw new IllegalArgumentException("emails[].email must contain @");
                }
                emails.add(new PersonStore.Email(email, e.getBoolean("isPrimary", false)));
            }
            List<PersonStore.Phone> phones = new ArrayList<>();
            for (JsonValue v : o.getJsonArray("phones") == null
                    ? JsonValue.EMPTY_JSON_ARRAY : o.getJsonArray("phones")) {
                JsonObject p = v.asJsonObject();
                String number = p.getString("number", "").trim();
                if (number.isEmpty()) {
                    throw new IllegalArgumentException("phones[].number is required");
                }
                String type = optString(p, "type");
                if (type != null && !PHONE_TYPES.contains(type)) {
                    throw new IllegalArgumentException("phones[].type must be one of " + PHONE_TYPES);
                }
                phones.add(new PersonStore.Phone(number, type, p.getBoolean("isPrimary", false)));
            }
            return new PersonStore.Person(0, optString(o, "title"), given, family,
                    optString(o, "preferredName"), optDate(o, "dateOfBirth"),
                    optDate(o, "deceasedDate"), optString(o, "notes"), emails, phones);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed person payload");
        }
    }

    static JsonObject toJson(PersonStore.Person person) {
        JsonObjectBuilder b = Json.createObjectBuilder().add("id", person.id());
        addNullable(b, "title", person.title());
        b.add("givenName", person.givenName());
        b.add("familyName", person.familyName());
        addNullable(b, "preferredName", person.preferredName());
        addNullable(b, "dateOfBirth", person.dateOfBirth() == null ? null : person.dateOfBirth().toString());
        addNullable(b, "deceasedDate", person.deceasedDate() == null ? null : person.deceasedDate().toString());
        addNullable(b, "notes", person.notes());
        JsonArrayBuilder emails = Json.createArrayBuilder();
        for (PersonStore.Email email : person.emails()) {
            emails.add(Json.createObjectBuilder()
                    .add("email", email.email())
                    .add("isPrimary", email.isPrimary()));
        }
        b.add("emails", emails);
        JsonArrayBuilder phones = Json.createArrayBuilder();
        for (PersonStore.Phone phone : person.phones()) {
            JsonObjectBuilder p = Json.createObjectBuilder().add("number", phone.number());
            addNullable(p, "type", phone.type());
            phones.add(p.add("isPrimary", phone.isPrimary()));
        }
        b.add("phones", phones);
        return b.build();
    }

    static void addNullable(JsonObjectBuilder b, String name, String value) {
        if (value == null) b.addNull(name); else b.add(name, value);
    }

    static void addNullable(JsonObjectBuilder b, String name, Integer value) {
        if (value == null) b.addNull(name); else b.add(name, value);
    }

    private static String optString(JsonObject o, String name) {
        String value = o.containsKey(name) && !o.isNull(name) ? o.getString(name).trim() : null;
        return value == null || value.isEmpty() ? null : value;
    }

    private static LocalDate optDate(JsonObject o, String name) {
        String value = optString(o, name);
        try {
            return value == null ? null : LocalDate.parse(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(name + " must be an ISO date (YYYY-MM-DD)");
        }
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"" + message + "\"}").build();
    }

    private static Response notFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"no such person\"}").build();
    }
}
