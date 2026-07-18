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
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server's Keycloak admin REST client, authenticated as the
 * {@code server-service} confidential client's service account
 * (realm-management view-users + manage-users + view-realm — the last for
 * reading role definitions, which the first two do not cover). This is
 * what turns a user's <em>claimed</em> role (the {@code claimed_role}
 * user-profile attribute, picked at registration or via /api/me/claim)
 * into a granted realm role, and what backs the admin panel's users
 * section.
 *
 * The claimable set is fixed: roles outside it ({@code admin},
 * {@code manager}) are never granted or revoked by claim handling, no
 * matter what a token or attribute says. The base URL and realm are
 * derived from the first KEYCLOAK_ISSUER entry; KEYCLOAK_ADMIN_URL
 * overrides the base (production 403s the public /auth/admin/* paths at
 * the proxy, so the war talks to Keycloak directly on the compose
 * network — http://keycloak:8080/auth. Safe because KC_HOSTNAME pins the
 * issuer to the public URL on every interface). The client credentials
 * come from KEYCLOAK_SERVICE_CLIENT / KEYCLOAK_SERVICE_SECRET (dev
 * defaults match the checked-in realm; deploy.sh rotates them for
 * production).
 */
final class KeycloakAdmin {

    /** Roles a user may claim for themselves. Everything else is admin-managed. */
    static final Set<String> CLAIMABLE = Set.of("member", "other");

    static final String CLAIM_ATTRIBUTE = "claimed_role";
    static final String VERIFIED_ATTRIBUTE = "role_verified";

    private static final KeycloakAdmin INSTANCE = fromEnv();
    /** Per-subject reconciliation guard: at most one sync per minute per user. */
    private static final Map<String, Long> RECENT_SYNC = new ConcurrentHashMap<>();
    private static final long SYNC_INTERVAL_MS = 60_000;

    private final String base;
    private final String realm;
    private final String clientId;
    private final String secret;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, JsonObject> roleReps = new ConcurrentHashMap<>();
    private volatile String token = "";
    private volatile long tokenExpiresMs = 0;

    private KeycloakAdmin(String base, String realm, String clientId, String secret) {
        this.base = base;
        this.realm = realm;
        this.clientId = clientId;
        this.secret = secret;
    }

    static KeycloakAdmin instance() {
        return INSTANCE;
    }

    private static KeycloakAdmin fromEnv() {
        // the first allowlisted issuer names the Keycloak the server itself talks to
        String issuer = AuthFilter.ISSUERS.get(0);
        int split = issuer.lastIndexOf("/realms/");
        if (split < 0) throw new IllegalStateException("issuer without /realms/: " + issuer);
        return new KeycloakAdmin(
                env("KEYCLOAK_ADMIN_URL", issuer.substring(0, split)),
                issuer.substring(split + "/realms/".length()),
                env("KEYCLOAK_SERVICE_CLIENT", "server-service"),
                env("KEYCLOAK_SERVICE_SECRET", "server-service-dev-secret"));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * Reconcile a caller's granted roles with the claim their token carried.
     * Cheap-detect happens in AuthFilter from the token alone; this re-reads
     * the CURRENT grants from Keycloak (the token may be stale), so a second
     * stale-token request after a successful sync is a no-op and does not
     * re-reset the verified flag. Failures are logged and swallowed — the
     * request proceeds with its token roles.
     */
    static void maybeReconcile(UserPrincipal user) {
        String claimed = user.getClaimedRole();
        if (claimed == null || !CLAIMABLE.contains(claimed)) {
            return; // no claim (grandfathered) or non-claimable junk: never touch roles
        }
        Set<String> tokenClaimable = new LinkedHashSet<>(user.getRoles());
        tokenClaimable.retainAll(CLAIMABLE);
        if (tokenClaimable.equals(Set.of(claimed))) {
            return; // token already reflects the claim
        }
        long now = System.currentTimeMillis();
        Long last = RECENT_SYNC.get(user.getName());
        if (last != null && now - last < SYNC_INTERVAL_MS) {
            return;
        }
        RECENT_SYNC.put(user.getName(), now);
        try {
            INSTANCE.syncClaim(user.getName(), claimed);
        } catch (Exception e) { // any failure here must never fail the request
            System.err.println("role reconciliation failed for sub "
                    + user.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Make the user's claimable grants exactly {claimed} (or none for null).
     * Returns true when any grant changed; a change resets role_verified —
     * the admin verified the OLD claim, not this one.
     */
    boolean syncClaim(String userId, String claimed) throws IOException {
        if (claimed != null && !CLAIMABLE.contains(claimed)) {
            throw new IOException("not a claimable role: " + claimed);
        }
        Set<String> current = realmRoles(userId);
        current.retainAll(CLAIMABLE);
        boolean changed = false;
        for (String role : current) {
            if (!role.equals(claimed)) {
                roleMapping("DELETE", userId, role);
                changed = true;
            }
        }
        if (claimed != null && !current.contains(claimed)) {
            roleMapping("POST", userId, claimed);
            changed = true;
        }
        if (changed) {
            updateAttributes(userId, Map.of(VERIFIED_ATTRIBUTE, "false"));
        }
        return changed;
    }

    /** The user's current realm-role names (mutable copy). */
    Set<String> realmRoles(String userId) throws IOException {
        JsonArray mapped = request("GET", "/users/" + userId + "/role-mappings/realm", null)
                .asJsonArray();
        Set<String> names = new LinkedHashSet<>();
        for (JsonValue v : mapped) {
            names.add(v.asJsonObject().getString("name"));
        }
        return names;
    }

    /** Grant or revoke one realm role (POST grants, DELETE revokes). */
    void roleMapping(String method, String userId, String roleName) throws IOException {
        JsonObject rep = roleReps.get(roleName);
        if (rep == null) {
            // needs realm-management view-realm (view-users/manage-users do
            // NOT cover reading role definitions); cache — roles are stable
            rep = request("GET", "/roles/" + encode(roleName), null).asJsonObject();
            roleReps.put(roleName, rep);
        }
        request(method, "/users/" + userId + "/role-mappings/realm",
                Json.createArrayBuilder().add(rep).build().toString());
    }

    /** Null user when the id is unknown. */
    JsonObject user(String userId) throws IOException {
        try {
            return request("GET", "/users/" + userId, null).asJsonObject();
        } catch (NotFoundException e) {
            return null;
        }
    }

    JsonArray findUsers(String search, int first, int max) throws IOException {
        String query = "?briefRepresentation=false&first=" + first + "&max=" + max
                + (search == null || search.isBlank() ? "" : "&search=" + encode(search));
        return request("GET", "/users" + query, null).asJsonArray();
    }

    /** Exact-email lookup (CR-006 provisioning); empty array when nobody carries it. */
    JsonArray findUsersByEmail(String email) throws IOException {
        return request("GET", "/users?briefRepresentation=false&exact=true&email=" + encode(email), null)
                .asJsonArray();
    }

    /**
     * Create a user (CR-006 provisioning) and return the new subject id.
     * emailVerified is the caller's call (register-provisioned accounts are
     * pre-verified, principle 2). lastName must be non-blank — REST-created
     * users missing the user-profile required fields fail login with
     * "Account is not fully set up". No credentials: first login is
     * Keycloak's Forgot Password flow.
     */
    String createUser(String username, String email, String firstName, String lastName,
                      boolean emailVerified) throws IOException {
        JsonObject rep = Json.createObjectBuilder()
                .add("username", username)
                .add("email", email)
                .add("firstName", firstName == null || firstName.isBlank() ? "-" : firstName)
                .add("lastName", lastName == null || lastName.isBlank() ? "-" : lastName)
                .add("emailVerified", emailVerified)
                .add("enabled", true)
                .build();
        // the id only rides in the 201's Location header, which request()
        // does not surface — so this call handles the response itself
        HttpRequest post = HttpRequest.newBuilder()
                .uri(URI.create(base + "/admin/realms/" + realm + "/users"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + serviceToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(rep.toString()))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(post, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted creating Keycloak user", e);
        }
        if (response.statusCode() != 201) {
            throw new IOException("Keycloak user creation for " + email + ": "
                    + response.statusCode() + " " + response.body());
        }
        String location = response.headers().firstValue("Location")
                .orElseThrow(() -> new IOException("user created but no Location header"));
        return location.substring(location.lastIndexOf('/') + 1);
    }

    /**
     * Merge attribute values into the user (null value removes the key).
     * Keycloak's PUT replaces the whole representation, so read-modify-write.
     */
    void updateAttributes(String userId, Map<String, String> values) throws IOException {
        JsonObject rep = user(userId);
        if (rep == null) throw new IOException("no such user: " + userId);
        Map<String, JsonValue> mutable = new HashMap<>(rep);
        Map<String, JsonValue> attributes = new HashMap<>(
                rep.containsKey("attributes") ? rep.getJsonObject("attributes") : Map.of());
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getValue() == null) {
                attributes.remove(e.getKey());
            } else {
                attributes.put(e.getKey(),
                        Json.createArrayBuilder().add(e.getValue()).build());
            }
        }
        mutable.put("attributes", Json.createObjectBuilder(attributes).build());
        request("PUT", "/users/" + userId,
                Json.createObjectBuilder(mutable).build().toString());
    }

    /** First value of a user attribute, or null. */
    static String attribute(JsonObject userRep, String name) {
        if (userRep == null || !userRep.containsKey("attributes")) return null;
        JsonObject attributes = userRep.getJsonObject("attributes");
        if (!attributes.containsKey(name)) return null;
        JsonArray values = attributes.getJsonArray(name);
        return values.isEmpty() ? null : values.getString(0);
    }

    static final class NotFoundException extends IOException {
        NotFoundException(String message) {
            super(message);
        }
    }

    private JsonValue request(String method, String path, String body) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(base + "/admin/realms/" + realm + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + serviceToken());
        if (body != null) {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response;
        try {
            response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted calling Keycloak", e);
        }
        if (response.statusCode() == 404) {
            throw new NotFoundException(method + " " + path + ": 404");
        }
        if (response.statusCode() >= 300) {
            throw new IOException("Keycloak admin " + method + " " + path + ": "
                    + response.statusCode() + " " + response.body());
        }
        String text = response.body();
        if (text == null || text.isBlank()) return JsonValue.NULL;
        try (JsonReader reader = Json.createReader(new StringReader(text))) {
            return reader.readValue();
        }
    }

    /** Client-credentials token for the service account, cached until expiry. */
    private synchronized String serviceToken() throws IOException {
        long now = System.currentTimeMillis();
        if (now < tokenExpiresMs - 30_000 && !token.isEmpty()) {
            return token;
        }
        String form = "grant_type=client_credentials&client_id=" + encode(clientId)
                + "&client_secret=" + encode(secret);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/realms/" + realm + "/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted minting service token", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("service token: " + response.statusCode() + " " + response.body());
        }
        try (JsonReader reader = Json.createReader(new StringReader(response.body()))) {
            JsonObject tok = reader.readObject();
            token = tok.getString("access_token");
            tokenExpiresMs = now + tok.getJsonNumber("expires_in").longValue() * 1000;
        }
        return token;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Role names worth showing in the admin panel (drop Keycloak's built-ins). */
    static List<String> displayRoles(Set<String> roleNames) {
        List<String> out = new ArrayList<>();
        for (String r : roleNames) {
            if (!r.startsWith("default-roles-") && !r.equals("offline_access")
                    && !r.equals("uma_authorization")) {
                out.add(r);
            }
        }
        out.sort(String::compareTo);
        return out;
    }
}
