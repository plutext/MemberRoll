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

import org.jdbi.v3.core.Jdbi;

import java.io.StringReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private final Jdbi jdbi = Db.jdbi();
    private final PersonStore store = new PersonStore(jdbi);

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
        try {
            PersonStore.Person created = store.create(draft);
            return Response.status(Response.Status.CREATED)
                    .entity(toJson(created).toString()).build();
        } catch (ConflictException e) {
            return conflict(e.getMessage());
        }
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
        try {
            return store.update(id, draft)
                    .map(person -> Response.ok(toJson(person).toString()).build())
                    .orElseGet(AdminPeopleResource::notFound);
        } catch (ConflictException e) {
            return conflict(e.getMessage());
        }
    }

    // ---- self-serve link (CR-006) ---------------------------------------

    /** Whether (and to which subject) this person is linked for self-serve. */
    @GET
    @Path("{id}/keycloak-link")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getKeycloakLink(@PathParam("id") long id) {
        return jdbi.withHandle(handle -> {
            if (!SelfServeStore.personExists(handle, id)) return notFound();
            String subject = SelfServeStore.subjectOf(handle, id).orElse(null);
            JsonObjectBuilder b = Json.createObjectBuilder()
                    .add("personId", id).add("linked", subject != null);
            addNullable(b, "subject", subject);
            return Response.ok(b.build().toString()).build();
        });
    }

    /**
     * Unlink (email reassigned, wrong link, member asks): nulls the column
     * and leaves the Keycloak account alone (CR-006 principle 7) — an
     * unlinked account can still log in and simply sees "no membership
     * linked". Idempotent: unlinking an unlinked person is a 200 no-op.
     */
    @jakarta.ws.rs.DELETE
    @Path("{id}/keycloak-link")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteKeycloakLink(@PathParam("id") long id) {
        return jdbi.withHandle(handle -> {
            if (!SelfServeStore.unlink(handle, id)) return notFound();
            return Response.ok(Json.createObjectBuilder()
                    .add("personId", id).add("linked", false).build().toString()).build();
        });
    }

    // ---- communication preferences (CR-005) ----------------------------

    @GET
    @Path("{id}/preferences")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getPreferences(@PathParam("id") long id) {
        return jdbi.withHandle(handle -> {
            if (!CommunicationPreferenceStore.personExists(handle, id)) return notFound();
            Long householdId = CommunicationPreferenceStore.currentHouseholdOf(handle, id);
            return Response.ok(preferencesJson(
                    CommunicationPreferenceStore.forPerson(handle, id, householdId)).toString()).build();
        });
    }

    @PUT
    @Path("{id}/preferences")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response putPreferences(@PathParam("id") long id, String body) {
        JsonObject request = readObject(body);
        if (request == null) return badRequest("body must be a JSON object");
        String type = upper(optString(request, "communicationType"));
        String method = upper(optString(request, "deliveryMethod"));
        if (type == null || !CommunicationPreferenceStore.COMMUNICATION_TYPES.contains(type)) {
            return badRequest("communicationType must be one of "
                    + CommunicationPreferenceStore.COMMUNICATION_TYPES);
        }
        if (method == null || !CommunicationPreferenceStore.DELIVERY_METHODS.contains(method)) {
            return badRequest("deliveryMethod must be one of "
                    + CommunicationPreferenceStore.DELIVERY_METHODS);
        }
        Response guard = jdbi.inTransaction(handle -> {
            if (!CommunicationPreferenceStore.personExists(handle, id)) return notFound();
            Long householdId = CommunicationPreferenceStore.currentHouseholdOf(handle, id);
            CommunicationPreferenceStore.putPerson(handle, id, householdId, type, method);
            return null;
        });
        if (guard != null) return guard;
        return jdbi.withHandle(handle -> Response.ok(preferencesJson(
                CommunicationPreferenceStore.forPerson(handle, id,
                        CommunicationPreferenceStore.currentHouseholdOf(handle, id))).toString()).build());
    }

    /** {type: {method, source}} for each of the four communication types — shared with households. */
    static JsonObject preferencesJson(Map<String, CommunicationPreferenceStore.Resolved> prefs) {
        JsonObjectBuilder inner = Json.createObjectBuilder();
        prefs.forEach((type, r) -> inner.add(type,
                Json.createObjectBuilder().add("method", r.method()).add("source", r.source())));
        return Json.createObjectBuilder().add("preferences", inner).build();
    }

    private static JsonObject readObject(String body) {
        try (JsonReader reader = Json.createReader(new StringReader(body == null ? "" : body))) {
            return reader.readObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(java.util.Locale.ROOT);
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
        return parseObject(o);
    }

    /**
     * Same parsing/validation as {@link #parse}, for a caller (CR-010's
     * composite new-member endpoint) that already has the person fields as a
     * nested {@link JsonObject} rather than the whole request body.
     */
    static PersonStore.Person parseObject(JsonObject o) {
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
            // CR-020: an absent/null memberNo on PUT clears it (the form's
            // wholesale-replace semantics, like emails and phones)
            Integer memberNo = null;
            if (o.containsKey("memberNo") && !o.isNull("memberNo")) {
                int n;
                try {
                    n = o.getJsonNumber("memberNo").intValueExact();
                } catch (Exception e) {
                    throw new IllegalArgumentException("memberNo must be a positive integer");
                }
                if (n <= 0) throw new IllegalArgumentException("memberNo must be a positive integer");
                memberNo = n;
            }
            return new PersonStore.Person(0, optString(o, "title"), given, family,
                    optString(o, "preferredName"), optDate(o, "dateOfBirth"),
                    optDate(o, "deceasedDate"), optString(o, "notes"), memberNo, emails, phones);
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
        addNullable(b, "memberNo", person.memberNo());
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

    private static Response conflict(String message) {
        return Response.status(Response.Status.CONFLICT)
                .entity("{\"error\":\"" + message + "\"}").build();
    }

    private static Response notFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"no such person\"}").build();
    }
}
