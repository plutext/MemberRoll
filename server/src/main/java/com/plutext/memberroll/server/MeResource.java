package com.plutext.memberroll.server;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The caller's own role claim. New users pick a claim on the hosted
 * registration page; this endpoint is the path for accounts that predate
 * the picker (the webapp prompts them) and for changing one's claim
 * later. Setting a claim resets the verified flag — the admin verified
 * the old claim, not the new one.
 */
@Path("me")
public class MeResource {

    @PUT
    @Path("claim")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response claim(@Context SecurityContext security, String body) {
        if (!(security.getUserPrincipal() instanceof UserPrincipal user)) {
            throw new NotAuthorizedException("Bearer realm=\"memberroll\"");
        }
        String role;
        try (JsonReader reader = Json.createReader(new StringReader(body == null ? "" : body))) {
            JsonObject request = reader.readObject();
            JsonValue value = request.getOrDefault("role", JsonValue.NULL);
            role = value.getValueType() == JsonValue.ValueType.NULL
                    ? null : request.getString("role");
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"body must be {\\\"role\\\": \\\"<role>\\\"} or {\\\"role\\\": null}\"}")
                    .build();
        }
        if (role != null && !KeycloakAdmin.CLAIMABLE.contains(role)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"not a claimable role: " + WhoAmIResource.escape(role)
                            + " (claimable: " + String.join(", ",
                                    KeycloakAdmin.CLAIMABLE.stream().sorted().toList()) + ")\"}")
                    .build();
        }
        KeycloakAdmin keycloak = KeycloakAdmin.instance();
        try {
            keycloak.updateAttributes(user.getName(),
                    java.util.Collections.singletonMap(KeycloakAdmin.CLAIM_ATTRIBUTE, role));
            boolean changed = keycloak.syncClaim(user.getName(), role);
            if (role == null) {
                // clearing the claim always clears verification too
                keycloak.updateAttributes(user.getName(),
                        Map.of(KeycloakAdmin.VERIFIED_ATTRIBUTE, "false"));
            }
            Set<String> claimable = new LinkedHashSet<>(keycloak.realmRoles(user.getName()));
            claimable.retainAll(KeycloakAdmin.CLAIMABLE);
            return Response.ok("{"
                    + (role == null ? "\"claimed_role\":null" :
                            "\"claimed_role\":\"" + WhoAmIResource.escape(role) + "\"")
                    + ",\"granted\":" + jsonArray(claimable)
                    + ",\"changed\":" + changed
                    + ",\"note\":\"takes effect in your next token (refresh or re-login)\"}")
                    .build();
        } catch (IOException e) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"error\":\"Keycloak admin call failed: "
                            + WhoAmIResource.escape(String.valueOf(e.getMessage())) + "\"}")
                    .build();
        }
    }

    private static String jsonArray(Set<String> values) {
        return "[" + values.stream().sorted()
                .map(v -> "\"" + WhoAmIResource.escape(v) + "\"")
                .reduce((a, b) -> a + "," + b).orElse("") + "]";
    }
}
