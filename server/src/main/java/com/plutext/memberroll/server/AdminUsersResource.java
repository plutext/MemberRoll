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
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.Map;

/**
 * The admin panel's users section. Lists Keycloak users with their
 * claimed role / verified flag / granted roles, corrects claims, sets
 * the verified flag, and grants or revokes {@code manager} — the role
 * nobody may claim. Admin role changes inside the claimable set go
 * through the CLAIM (not a direct grant) so reconciliation can never
 * fight them; {@code manager} is outside that set, so a direct grant is
 * safe and is the only way to get it.
 */
@Path("admin/users")
@RolesAllowed("admin")
public class AdminUsersResource {

    /** The one app role granted directly (never claimable). */
    static final String GRANTED_ROLE = "manager";

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("search") String search,
                         @QueryParam("first") @DefaultValue("0") int first,
                         @QueryParam("max") @DefaultValue("50") int max) {
        KeycloakAdmin keycloak = KeycloakAdmin.instance();
        try {
            JsonArrayBuilder out = Json.createArrayBuilder();
            for (JsonValue v : keycloak.findUsers(search, Math.max(0, first),
                    Math.min(Math.max(1, max), 200))) {
                JsonObject u = v.asJsonObject();
                String id = u.getString("id");
                if (u.getString("username", "").startsWith("service-account-")) {
                    continue; // machinery, not a person
                }
                String claimed = KeycloakAdmin.attribute(u, KeycloakAdmin.CLAIM_ATTRIBUTE);
                boolean verified = "true".equals(
                        KeycloakAdmin.attribute(u, KeycloakAdmin.VERIFIED_ATTRIBUTE));
                JsonObjectBuilder row = Json.createObjectBuilder()
                        .add("id", id)
                        .add("username", u.getString("username", ""))
                        .add("email", u.getString("email", ""))
                        .add("firstName", u.getString("firstName", ""))
                        .add("lastName", u.getString("lastName", ""))
                        .add("verified", verified);
                if (claimed == null) {
                    row.addNull("claimed_role");
                } else {
                    row.add("claimed_role", claimed);
                }
                JsonArrayBuilder roles = Json.createArrayBuilder();
                for (String r : KeycloakAdmin.displayRoles(keycloak.realmRoles(id))) {
                    roles.add(r);
                }
                row.add("roles", roles);
                out.add(row);
            }
            return Response.ok(out.build().toString()).build();
        } catch (IOException e) {
            return keycloakError(e);
        }
    }

    /** Correct a user's claim (and optionally verify it in the same call). */
    @PUT
    @Path("{id}/claim")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setClaim(@PathParam("id") String id, String body) {
        String role;
        boolean verified;
        try (JsonReader reader = Json.createReader(new StringReader(body == null ? "" : body))) {
            JsonObject request = reader.readObject();
            JsonValue value = request.getOrDefault("role", JsonValue.NULL);
            role = value.getValueType() == JsonValue.ValueType.NULL
                    ? null : request.getString("role");
            verified = request.getBoolean("verified", false);
        } catch (Exception e) {
            return badRequest("body must be {\\\"role\\\": <role-or-null>, \\\"verified\\\": <optional bool>}");
        }
        if (role != null && !KeycloakAdmin.CLAIMABLE.contains(role)) {
            return badRequest("not a claimable role: " + WhoAmIResource.escape(role));
        }
        if (role == null && verified) {
            return badRequest("cannot verify an empty claim");
        }
        KeycloakAdmin keycloak = KeycloakAdmin.instance();
        try {
            if (keycloak.user(id) == null) return notFound();
            keycloak.updateAttributes(id,
                    Collections.singletonMap(KeycloakAdmin.CLAIM_ATTRIBUTE, role));
            keycloak.syncClaim(id, role);
            keycloak.updateAttributes(id,
                    Map.of(KeycloakAdmin.VERIFIED_ATTRIBUTE, String.valueOf(verified)));
            return Response.ok(userJson(keycloak, id)).build();
        } catch (IOException e) {
            return keycloakError(e);
        }
    }

    /** Flag a user's existing claim as checked-as-fact (or unflag it). */
    @PUT
    @Path("{id}/verified")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setVerified(@PathParam("id") String id, String body) {
        boolean verified;
        try (JsonReader reader = Json.createReader(new StringReader(body == null ? "" : body))) {
            verified = reader.readObject().getBoolean("verified");
        } catch (Exception e) {
            return badRequest("body must be {\\\"verified\\\": true|false}");
        }
        KeycloakAdmin keycloak = KeycloakAdmin.instance();
        try {
            JsonObject user = keycloak.user(id);
            if (user == null) return notFound();
            if (KeycloakAdmin.attribute(user, KeycloakAdmin.CLAIM_ATTRIBUTE) == null) {
                return Response.status(Response.Status.CONFLICT)
                        .entity("{\"error\":\"user has no role claim to verify\"}").build();
            }
            keycloak.updateAttributes(id,
                    Map.of(KeycloakAdmin.VERIFIED_ATTRIBUTE, String.valueOf(verified)));
            return Response.ok(userJson(keycloak, id)).build();
        } catch (IOException e) {
            return keycloakError(e);
        }
    }

    /** Grant or revoke manager — admin-only by design (no claim path). */
    @PUT
    @Path("{id}/manager")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setManager(@PathParam("id") String id, String body) {
        boolean granted;
        try (JsonReader reader = Json.createReader(new StringReader(body == null ? "" : body))) {
            granted = reader.readObject().getBoolean("granted");
        } catch (Exception e) {
            return badRequest("body must be {\\\"granted\\\": true|false}");
        }
        KeycloakAdmin keycloak = KeycloakAdmin.instance();
        try {
            JsonObject user = keycloak.user(id);
            if (user == null) return notFound();
            boolean has = keycloak.realmRoles(id).contains(GRANTED_ROLE);
            if (granted && !has) keycloak.roleMapping("POST", id, GRANTED_ROLE);
            if (!granted && has) keycloak.roleMapping("DELETE", id, GRANTED_ROLE);
            return Response.ok(userJson(keycloak, id)).build();
        } catch (IOException e) {
            return keycloakError(e);
        }
    }

    private static String userJson(KeycloakAdmin keycloak, String id) throws IOException {
        JsonObject u = keycloak.user(id);
        String claimed = KeycloakAdmin.attribute(u, KeycloakAdmin.CLAIM_ATTRIBUTE);
        JsonObjectBuilder row = Json.createObjectBuilder()
                .add("id", id)
                .add("username", u.getString("username", ""))
                .add("verified", "true".equals(
                        KeycloakAdmin.attribute(u, KeycloakAdmin.VERIFIED_ATTRIBUTE)));
        if (claimed == null) {
            row.addNull("claimed_role");
        } else {
            row.add("claimed_role", claimed);
        }
        JsonArrayBuilder roles = Json.createArrayBuilder();
        for (String r : KeycloakAdmin.displayRoles(keycloak.realmRoles(id))) {
            roles.add(r);
        }
        return row.add("roles", roles).build().toString();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"" + message + "\"}").build();
    }

    private static Response notFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"no such user\"}").build();
    }

    private static Response keycloakError(IOException e) {
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity("{\"error\":\"Keycloak admin call failed: "
                        + WhoAmIResource.escape(String.valueOf(e.getMessage())) + "\"}")
                .build();
    }
}
