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
import jakarta.json.JsonReader;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.io.IOException;
import java.io.StringReader;

/**
 * The worked example of an own-data REST resource: every authenticated
 * user gets a private notes collection, keyed by their token subject.
 * The access rule to copy: callers act on their OWN data; {@code admin}
 * may read and delete anyone's by naming the owner ({@code ?owner=<sub>});
 * nobody else can see a foreign note even exists (404, not 403 — the
 * store is keyed by owner, so a foreign id simply isn't found).
 *
 * PUT is create-or-replace with a caller-chosen id, so retries are
 * idempotent — the same convention the TurbinePreview photo store used
 * to make flaky-network uploads safe.
 */
@Path("notes")
public class NotesResource {

    private final NoteStore store = NoteStore.fromEnv();

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@Context SecurityContext security) {
        UserPrincipal user = require(security);
        try {
            JsonArrayBuilder out = Json.createArrayBuilder();
            for (JsonObject note : store.list(user.getName())) {
                out.add(note);
            }
            return Response.ok(out.build().toString()).build();
        } catch (IOException e) {
            return storeError(e);
        }
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response put(@Context SecurityContext security,
                        @PathParam("id") String id, String body) {
        UserPrincipal user = require(security);
        if (!NoteStore.isValidId(id)) {
            return badRequest("note id: letters, digits, - _ (max 64)");
        }
        if (body != null && body.length() > NoteStore.MAX_NOTE_BYTES) {
            return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                    .entity("{\"error\":\"note too large\"}").build();
        }
        String title;
        String text;
        try (JsonReader reader = Json.createReader(new StringReader(body == null ? "" : body))) {
            JsonObject request = reader.readObject();
            title = request.getString("title");
            text = request.getString("body", "");
        } catch (Exception e) {
            return badRequest("body must be {\\\"title\\\": ..., \\\"body\\\": ...}");
        }
        try {
            return Response.ok(store.put(user.getName(), id, title, text)).build();
        } catch (IOException e) {
            return storeError(e);
        }
    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@Context SecurityContext security,
                        @PathParam("id") String id, @QueryParam("owner") String owner) {
        UserPrincipal user = require(security);
        if (!NoteStore.isValidId(id)) {
            return badRequest("note id: letters, digits, - _ (max 64)");
        }
        String effectiveOwner = resolveOwner(user, owner);
        if (effectiveOwner == null) {
            return forbidden();
        }
        try {
            String note = store.get(effectiveOwner, id);
            return note == null ? notFound() : Response.ok(note).build();
        } catch (IOException e) {
            return storeError(e);
        }
    }

    @DELETE
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@Context SecurityContext security,
                           @PathParam("id") String id, @QueryParam("owner") String owner) {
        UserPrincipal user = require(security);
        if (!NoteStore.isValidId(id)) {
            return badRequest("note id: letters, digits, - _ (max 64)");
        }
        String effectiveOwner = resolveOwner(user, owner);
        if (effectiveOwner == null) {
            return forbidden();
        }
        try {
            return store.delete(effectiveOwner, id)
                    ? Response.ok("{\"deleted\":true}").build()
                    : notFound();
        } catch (IOException e) {
            return storeError(e);
        }
    }

    /** The 401-challenge-for-guests pattern (same as WhoAmIResource). */
    private static UserPrincipal require(SecurityContext security) {
        if (!(security.getUserPrincipal() instanceof UserPrincipal user)) {
            throw new NotAuthorizedException("Bearer realm=\"memberroll\"");
        }
        return user;
    }

    /**
     * The owner whose data this request touches: yourself by default;
     * someone else only for admin. Null means the caller asked for a
     * foreign owner without the admin role.
     */
    private static String resolveOwner(UserPrincipal user, String owner) {
        if (owner == null || owner.isBlank() || owner.equals(user.getName())) {
            return user.getName();
        }
        return user.getRoles().contains("admin") ? owner : null;
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"" + message + "\"}").build();
    }

    private static Response notFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"no such note\"}").build();
    }

    private static Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity("{\"error\":\"only admin may name another owner\"}").build();
    }

    private static Response storeError(IOException e) {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\":\"store failure: "
                        + WhoAmIResource.escape(String.valueOf(e.getMessage())) + "\"}")
                .build();
    }
}
