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
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.mail.internet.InternetAddress;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import org.jdbi.v3.core.Jdbi;

import java.io.StringReader;
import java.util.Optional;

/**
 * SMTP settings admin API (CR-014), admin-only. Reads/writes the one
 * {@code app_setting} row {@link Mail#SETTINGS_KEY}, whose JSON value is the
 * page-configured relay ({@code Mail} resolves PAGE → ENV → NONE per send).
 *
 * <p>The password is write-only from the client's point of view: it is never
 * returned (only {@code passwordSet}) and never logged. On a re-save an absent
 * {@code password} keeps the stored one, an empty string clears it — so the
 * admin never has to retype the secret to change the host. {@code POST /test}
 * is the one place a send error surfaces to a human (verbatim, but password
 * scrubbed) — the test-before-save flow, using candidate settings from the
 * body, writing nothing.
 */
@Path("admin/mail-settings")
@RolesAllowed("admin")
public class AdminMailSettingsResource {

    private final Jdbi jdbi = Db.jdbi();

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response get() {
        return Response.ok(effectiveJson(Mail.resolve()).toString()).build();
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response put(String body, @Context SecurityContext security) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");

        String host = Payloads.optString(request, "host");
        if (host == null) return badRequest("host is required");

        Integer port;
        try {
            port = Payloads.optInt(request, "port");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        if (port == null) return badRequest("port is required");
        if (port < 1 || port > 65535) return badRequest("port must be between 1 and 65535");

        String security2 = upper(Payloads.optString(request, "security"));
        if (security2 == null || !isSecurity(security2)) {
            return badRequest("security must be one of NONE, STARTTLS, SSL");
        }

        String from = Payloads.optString(request, "from");
        if (from == null) return badRequest("from is required");
        if (!parseable(from)) return badRequest("from must be a valid email address");

        String replyTo = Payloads.optString(request, "replyTo");
        if (replyTo != null && !parseable(replyTo)) return badRequest("replyTo must be a valid email address");

        String username = Payloads.optString(request, "username");

        // password: absent → keep the stored one; "" → clear; otherwise → set.
        // The keep case reads the PAGE row specifically (never ENV) so a first
        // save with no password does not accidentally persist an env secret.
        String password;
        PasswordAction action = passwordAction(request);
        if (action == PasswordAction.KEEP) {
            password = storedPassword().orElse(null);
        } else if (action == PasswordAction.CLEAR) {
            password = null;
        } else {
            password = rawString(request, "password");
        }
        if (password != null && !password.isEmpty() && username == null) {
            return badRequest("password is only meaningful with a username");
        }

        JsonObjectBuilder value = Json.createObjectBuilder()
                .add("host", host).add("port", port).add("security", security2).add("from", from);
        if (username != null) value.add("username", username);
        if (password != null && !password.isEmpty()) value.add("password", password);
        if (replyTo != null) value.add("replyTo", replyTo);
        String json = value.build().toString();

        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO app_setting (key, value, updated_by) VALUES (:k, :v, :by)"
                        + " ON CONFLICT (key) DO UPDATE SET value = :v, updated_by = :by, updated_at = now()")
                .bind("k", Mail.SETTINGS_KEY).bind("v", json).bind("by", whom(security)).execute());

        return Response.ok(effectiveJson(Mail.resolve()).toString()).build();
    }

    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete() {
        jdbi.useHandle(h -> h.createUpdate("DELETE FROM app_setting WHERE key = :k")
                .bind("k", Mail.SETTINGS_KEY).execute());
        // reverts to ENV (or NONE where no env is set)
        return Response.ok(effectiveJson(Mail.resolve()).toString()).build();
    }

    @POST
    @Path("test")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response test(String body) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");

        String to = Payloads.optString(request, "to");
        if (to == null || !to.contains("@")) return badRequest("to must be an email address");

        Mail.Settings candidate;
        try {
            candidate = candidateSettings(request);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        String subject = "Test message from " + Mail.societyName();
        String text = "This is a test of the mail settings for " + Mail.societyName()
                + ".\n\nIf you received this, the SMTP configuration works.";
        String error = Mail.test(candidate, to, subject, text);

        JsonObjectBuilder out = Json.createObjectBuilder().add("ok", error == null);
        if (error != null) out.add("error", error);
        return Response.ok(out.build().toString()).build();
    }

    // ---- helpers ------------------------------------------------------------

    /** Build candidate settings for a test send: an absent password uses the stored one. */
    private Mail.Settings candidateSettings(JsonObject request) {
        String host = Payloads.optString(request, "host");
        if (host == null) throw new IllegalArgumentException("host is required");
        Integer port = Payloads.optInt(request, "port");
        if (port == null) throw new IllegalArgumentException("port is required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be between 1 and 65535");
        String security = upper(Payloads.optString(request, "security"));
        if (security == null || !isSecurity(security)) {
            throw new IllegalArgumentException("security must be one of NONE, STARTTLS, SSL");
        }
        String from = Payloads.optString(request, "from");
        if (from == null) throw new IllegalArgumentException("from is required");
        if (!parseable(from)) throw new IllegalArgumentException("from must be a valid email address");
        String replyTo = Payloads.optString(request, "replyTo");
        if (replyTo != null && !parseable(replyTo)) {
            throw new IllegalArgumentException("replyTo must be a valid email address");
        }
        String username = Payloads.optString(request, "username");
        String password = passwordAction(request) == PasswordAction.KEEP
                ? storedPassword().orElse(null)
                : rawString(request, "password");
        return new Mail.Settings(Mail.Source.PAGE, host, port,
                Mail.Security.valueOf(security), username, password, from, replyTo);
    }

    /** The GET/PUT/DELETE response shape: effective settings + provenance, never the password. */
    private static JsonObject effectiveJson(Mail.Settings s) {
        JsonObjectBuilder b = Json.createObjectBuilder().add("source", s.source().name());
        if (s.source() == Mail.Source.NONE) {
            return b.add("passwordSet", false).build();
        }
        addNullable(b, "host", s.host());
        b.add("port", s.port()).add("security", s.security().name());
        addNullable(b, "username", s.username());
        addNullable(b, "from", s.from());
        addNullable(b, "replyTo", s.replyTo());
        return b.add("passwordSet", s.passwordSet()).build();
    }

    /** The stored PAGE row's password, if a row exists and carries one. */
    private Optional<String> storedPassword() {
        Optional<String> json = jdbi.withHandle(h ->
                h.createQuery("SELECT value FROM app_setting WHERE key = :k")
                        .bind("k", Mail.SETTINGS_KEY).mapTo(String.class).findOne());
        if (json.isEmpty()) return Optional.empty();
        try (jakarta.json.JsonReader reader = Json.createReader(new StringReader(json.get()))) {
            JsonObject o = reader.readObject();
            if (o.containsKey("password") && !o.isNull("password")) {
                String p = o.getString("password");
                return p.isEmpty() ? Optional.empty() : Optional.of(p);
            }
        } catch (Exception ignored) {
            // unparseable row → nothing to keep
        }
        return Optional.empty();
    }

    private enum PasswordAction { KEEP, CLEAR, SET }

    private static PasswordAction passwordAction(JsonObject request) {
        if (!request.containsKey("password") || request.isNull("password")) return PasswordAction.KEEP;
        String raw = rawString(request, "password");
        return raw == null || raw.isEmpty() ? PasswordAction.CLEAR : PasswordAction.SET;
    }

    /** A raw (un-trimmed) string field, or null if absent/null — the password must not be trimmed. */
    private static String rawString(JsonObject o, String key) {
        if (!o.containsKey(key) || o.isNull(key)) return null;
        try {
            return o.getString(key);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isSecurity(String s) {
        return "NONE".equals(s) || "STARTTLS".equals(s) || "SSL".equals(s);
    }

    private static boolean parseable(String address) {
        try {
            InternetAddress ia = new InternetAddress(address, true);
            ia.validate();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void addNullable(JsonObjectBuilder b, String key, String value) {
        if (value == null) b.addNull(key); else b.add(key, value);
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(java.util.Locale.ROOT);
    }

    private static String whom(SecurityContext security) {
        return security.getUserPrincipal() instanceof UserPrincipal user ? user.getUsername() : "admin";
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Json.createObjectBuilder().add("error", message).build().toString()).build();
    }
}
