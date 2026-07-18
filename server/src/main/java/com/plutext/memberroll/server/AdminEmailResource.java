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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import org.jdbi.v3.core.Jdbi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Segment email admin API (CR-005), admin-only. Templates carry {@code
 * {{merge}}} fields validated strictly at BOTH save and send; a "segment" is
 * the CR-003 period+status/type view; a send resolves that segment to
 * recipients (MEMBER-only, communication-preference honoured, addresses
 * deduped) and runs sequentially on the mail thread with a visible ABORTED
 * state and a Resume button instead of a queue. Preview writes nothing and is
 * the intended dry-run.
 */
@Path("admin/email")
@RolesAllowed("admin")
public class AdminEmailResource {

    private final Jdbi jdbi = Db.jdbi();

    // ---- templates ----------------------------------------------------------

    @GET
    @Path("templates")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listTemplates() {
        JsonArrayBuilder out = Json.createArrayBuilder();
        for (EmailStore.Template t : jdbi.withHandle(EmailStore::listTemplates)) out.add(templateJson(t));
        return Response.ok(Json.createObjectBuilder().add("templates", out)
                .add("fields", fieldsJson())
                .add("mailEnabled", Mail.enabled()).build().toString()).build();
    }

    @POST
    @Path("templates")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createTemplate(String body, @Context SecurityContext security) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        try {
            String name = Payloads.optString(request, "name");
            String subject = Payloads.optString(request, "subject");
            String text = rawString(request, "body");
            if (name == null || subject == null || text == null) {
                return badRequest("name, subject and body are required");
            }
            MergeFields.validate(subject);
            MergeFields.validate(text);
            EmailStore.Template created = jdbi.inTransaction(h ->
                    EmailStore.createTemplate(h, name, subject, text, whom(security)));
            return Response.status(Response.Status.CREATED).entity(templateJson(created).toString()).build();
        } catch (ConflictException e) {
            return conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PUT
    @Path("templates/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateTemplate(@PathParam("id") long id, String body, @Context SecurityContext security) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        try {
            String name = Payloads.optString(request, "name");
            String subject = Payloads.optString(request, "subject");
            String text = rawString(request, "body");
            if (name == null || subject == null || text == null) {
                return badRequest("name, subject and body are required");
            }
            MergeFields.validate(subject);
            MergeFields.validate(text);
            return jdbi.inTransaction(h ->
                    EmailStore.updateTemplate(h, id, name, subject, text, whom(security)))
                    .map(t -> Response.ok(templateJson(t).toString()).build())
                    .orElseGet(() -> notFound("no such template"));
        } catch (ConflictException e) {
            return conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @DELETE
    @Path("templates/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteTemplate(@PathParam("id") long id) {
        return jdbi.inTransaction(h -> EmailStore.deleteTemplate(h, id))
                ? Response.ok(Json.createObjectBuilder().add("deleted", id).build().toString()).build()
                : notFound("no such template");
    }

    /** Proof-read a template against obviously-fake sample data; mints no token. */
    @POST
    @Path("templates/{id}/test")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response testSend(@PathParam("id") long id, String body) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        String to = Payloads.optString(request, "to");
        if (to == null || !to.contains("@")) return badRequest("to must be an email address");
        EmailStore.Template template = jdbi.withHandle(h -> EmailStore.getTemplate(h, id)).orElse(null);
        if (template == null) return notFound("no such template");
        String footer = jdbi.withHandle(EmailStore::getFooter);
        String composed = compose(template.body(), footer);
        try {
            MergeFields.validate(template.subject());
            MergeFields.validate(composed);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        Map<String, String> sample = sampleValues();
        String subject = MergeFields.render(template.subject(), sample);
        String rendered = MergeFields.render(composed, sample);
        Mail.sendAsync(to, subject, rendered);
        return Response.ok(Json.createObjectBuilder()
                .add("message", "test email queued to " + to + (Mail.enabled() ? "" : " (mail is disabled)"))
                .add("subject", subject).add("body", rendered).build().toString()).build();
    }

    // ---- footer -------------------------------------------------------------

    @GET
    @Path("footer")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getFooter() {
        return Response.ok(Json.createObjectBuilder()
                .add("text", jdbi.withHandle(EmailStore::getFooter)).build().toString()).build();
    }

    @PUT
    @Path("footer")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response putFooter(String body, @Context SecurityContext security) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        String text = rawString(request, "text");
        if (text == null) text = "";
        try {
            MergeFields.validate(text);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        String value = text;
        jdbi.useTransaction(h -> EmailStore.putFooter(h, value, whom(security)));
        return Response.ok(Json.createObjectBuilder().add("text", value).build().toString()).build();
    }

    // ---- preview / send -----------------------------------------------------

    @POST
    @Path("preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response preview(String body) {
        SendRequest req;
        try {
            req = parseSendRequest(body);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        return jdbi.withHandle(h -> {
            EmailStore.Segment segment = EmailStore.resolveSegment(
                    h, req.periodId, req.statusFilter, req.typeFilter, req.communicationType);
            JsonObject sample = renderSample(h, segment, req.subjectSnapshot, req.bodySnapshot);
            return Response.ok(previewJson(segment, sample).toString()).build();
        });
    }

    @POST
    @Path("sends")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createSend(String body, @Context SecurityContext security) {
        SendRequest req;
        try {
            req = parseSendRequest(body);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        long sendId;
        try {
            sendId = jdbi.inTransaction(h -> {
                if (EmailStore.anyRunning(h)) {
                    throw new ConflictException("a send is already running — wait for it to finish or resume it");
                }
                EmailStore.Segment segment = EmailStore.resolveSegment(
                        h, req.periodId, req.statusFilter, req.typeFilter, req.communicationType);
                return EmailStore.createSend(h, req.templateId, req.subjectSnapshot, req.bodySnapshot,
                        req.periodId, req.statusFilter, req.typeFilter, req.communicationType,
                        whom(security), segment);
            });
        } catch (ConflictException e) {
            return conflict(e.getMessage());
        }
        EmailStore.startSending(jdbi, sendId); // after commit: the rows must be visible to the mail thread
        return Response.status(Response.Status.CREATED)
                .entity(Json.createObjectBuilder().add("id", sendId).build().toString()).build();
    }

    @GET
    @Path("sends")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listSends() {
        JsonArrayBuilder out = Json.createArrayBuilder();
        for (EmailStore.SendSummary s : jdbi.withHandle(EmailStore::listSends)) out.add(sendJson(s));
        return Response.ok(Json.createObjectBuilder().add("sends", out).build().toString()).build();
    }

    @GET
    @Path("sends/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSend(@PathParam("id") long id) {
        return jdbi.withHandle(h -> {
            EmailStore.SendSummary send = EmailStore.getSend(h, id).orElse(null);
            if (send == null) return notFound("no such send");
            JsonArrayBuilder rows = Json.createArrayBuilder();
            for (EmailStore.RecipientRow r : EmailStore.recipients(h, id)) rows.add(recipientJson(r));
            JsonObjectBuilder b = Json.createObjectBuilder(sendJson(send));
            return Response.ok(b.add("recipients", rows).build().toString()).build();
        });
    }

    @POST
    @Path("sends/{id}/resume")
    @Produces(MediaType.APPLICATION_JSON)
    public Response resume(@PathParam("id") long id) {
        Response guard = jdbi.inTransaction(h -> {
            EmailStore.SendSummary send = EmailStore.getSend(h, id).orElse(null);
            if (send == null) return notFound("no such send");
            if ("RUNNING".equals(send.status())) return conflict("this send is already running");
            EmailStore.reenqueue(h, id);
            return null;
        });
        if (guard != null) return guard;
        EmailStore.startSending(jdbi, id);
        return jdbi.withHandle(h -> Response.ok(sendJson(EmailStore.getSend(h, id).orElseThrow())
                .toString()).build());
    }

    // ---- request parsing ----------------------------------------------------

    private static final class SendRequest {
        Long templateId;
        long periodId;
        String statusFilter;
        Long typeFilter;
        String communicationType;
        String subjectSnapshot;
        String bodySnapshot;
    }

    /** Shared by preview and send: resolves the template, composes+snapshots, validates merge fields. */
    private SendRequest parseSendRequest(String body) {
        JsonObject request = Payloads.read(body);
        if (request == null) throw new IllegalArgumentException("body must be a JSON object");
        SendRequest r = new SendRequest();
        r.templateId = Payloads.reqLong(request, "templateId");
        r.periodId = Payloads.reqLong(request, "periodId");
        r.statusFilter = upper(Payloads.optString(request, "statusFilter"));
        r.typeFilter = Payloads.optLong(request, "typeFilter");
        r.communicationType = upper(Payloads.optString(request, "communicationType"));
        if (r.communicationType == null
                || !CommunicationPreferenceStore.COMMUNICATION_TYPES.contains(r.communicationType)) {
            throw new IllegalArgumentException("communicationType must be one of "
                    + CommunicationPreferenceStore.COMMUNICATION_TYPES);
        }
        String footerOverride = request.containsKey("footer") && !request.isNull("footer")
                ? request.getString("footer") : null;
        return jdbi.withHandle(h -> {
            if (!PeriodStore.exists(h, r.periodId)) throw new IllegalArgumentException("no such membership period");
            EmailStore.Template template = EmailStore.getTemplate(h, r.templateId).orElse(null);
            if (template == null) throw new IllegalArgumentException("no such template");
            String footer = footerOverride != null ? footerOverride : EmailStore.getFooter(h);
            r.subjectSnapshot = template.subject();
            r.bodySnapshot = compose(template.body(), footer);
            MergeFields.validate(r.subjectSnapshot);
            MergeFields.validate(r.bodySnapshot);
            return r;
        });
    }

    // ---- sample rendering ---------------------------------------------------

    /** The first PENDING recipient rendered with REAL data + a sample (not minted) pay URL. */
    private JsonObject renderSample(org.jdbi.v3.core.Handle h, EmailStore.Segment segment,
                                    String subjectSnapshot, String bodySnapshot) {
        for (EmailStore.SegmentMembership sm : segment.memberships()) {
            for (EmailStore.PlannedRecipient r : sm.recipients()) {
                if ("PENDING".equals(r.status())) {
                    Map<String, String> values = EmailStore.mergeValues(
                            h, sm.membershipId(), r.personId(), PayResource.payUrl("EXAMPLE-LINK"));
                    return Json.createObjectBuilder()
                            .add("subject", MergeFields.render(subjectSnapshot, values))
                            .add("body", MergeFields.render(bodySnapshot, values)).build();
                }
            }
        }
        return null;
    }

    /** Obviously-fake proof-reading data; a URL-less placeholder for the pay link (nothing is minted). */
    private static Map<String, String> sampleValues() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("givenName", "Alex");
        v.put("familyName", "Example");
        v.put("displayName", "Alex Example");
        v.put("periodName", "2026-2027");
        v.put("typeName", "Household");
        v.put("amountDue", "$65.00");
        v.put("amountPaid", "$0.00");
        v.put("balance", "$65.00");
        v.put("payLink", "[pay link appears here]");
        v.put("societyName", Mail.societyName());
        return v;
    }

    // ---- JSON ---------------------------------------------------------------

    private static JsonObject templateJson(EmailStore.Template t) {
        return Json.createObjectBuilder().add("id", t.id()).add("name", t.name())
                .add("subject", t.subject()).add("body", t.body())
                .add("updatedBy", t.updatedBy()).add("updatedAt", t.updatedAt().toString()).build();
    }

    private static jakarta.json.JsonArray fieldsJson() {
        JsonArrayBuilder b = Json.createArrayBuilder();
        for (String f : MergeFields.FIELDS) b.add(f);
        return b.build();
    }

    private static JsonObject previewJson(EmailStore.Segment segment, JsonObject sample) {
        JsonArrayBuilder toSend = Json.createArrayBuilder();
        JsonArrayBuilder skippedPost = Json.createArrayBuilder();
        JsonArrayBuilder skippedNone = Json.createArrayBuilder();
        JsonArrayBuilder noEmail = Json.createArrayBuilder();
        int nSend = 0;
        int nPost = 0;
        int nNone = 0;
        int nNo = 0;
        for (EmailStore.SegmentMembership sm : segment.memberships()) {
            for (EmailStore.PlannedRecipient r : sm.recipients()) {
                JsonObjectBuilder row = Json.createObjectBuilder()
                        .add("membershipId", sm.membershipId()).add("displayName", sm.displayName());
                String name = trimName(r.givenName(), r.familyName());
                switch (r.status()) {
                    case "PENDING" -> { toSend.add(row.add("personName", name).add("email", r.email())); nSend++; }
                    case "SKIPPED_POST" -> { skippedPost.add(row.add("personName", name).add("email", r.email())); nPost++; }
                    case "SKIPPED_NONE" -> { skippedNone.add(row.add("personName", name).add("email", r.email())); nNone++; }
                    default -> { noEmail.add(row); nNo++; } // NO_EMAIL
                }
            }
        }
        JsonObjectBuilder countsB = Json.createObjectBuilder()
                .add("memberships", segment.memberships().size())
                .add("toSend", nSend).add("skippedPost", nPost)
                .add("skippedNone", nNone).add("noEmail", nNo);
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("counts", countsB)
                .add("toSend", toSend).add("skippedPost", skippedPost)
                .add("skippedNone", skippedNone).add("noEmail", noEmail)
                .add("smsWarning", segment.smsSeen());
        if (sample == null) b.addNull("sample"); else b.add("sample", sample);
        return b.build();
    }

    private static JsonObject sendJson(EmailStore.SendSummary s) {
        JsonObjectBuilder b = Json.createObjectBuilder().add("id", s.id());
        if (s.templateId() == null) b.addNull("templateId"); else b.add("templateId", s.templateId());
        AdminPeopleResource.addNullable(b, "templateName", s.templateName());
        b.add("subject", s.subject()).add("body", s.body())
                .add("periodId", s.periodId()).add("periodName", s.periodName());
        AdminPeopleResource.addNullable(b, "statusFilter", s.statusFilter());
        if (s.typeFilter() == null) b.addNull("typeFilter"); else b.add("typeFilter", s.typeFilter());
        AdminPeopleResource.addNullable(b, "typeName", s.typeName());
        b.add("communicationType", s.communicationType()).add("status", s.status())
                .add("createdBy", s.createdBy()).add("createdAt", s.createdAt().toString());
        AdminPeopleResource.addNullable(b, "finishedAt", s.finishedAt() == null ? null : s.finishedAt().toString());
        JsonObjectBuilder counts = Json.createObjectBuilder();
        s.counts().forEach(counts::add);
        return b.add("counts", counts).build();
    }

    private static JsonObject recipientJson(EmailStore.RecipientRow r) {
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("id", r.id()).add("membershipId", r.membershipId()).add("displayName", r.displayName());
        if (r.personId() == null) b.addNull("personId"); else b.add("personId", r.personId());
        AdminPeopleResource.addNullable(b, "personName", r.personName());
        AdminPeopleResource.addNullable(b, "email", r.email());
        b.add("status", r.status());
        AdminPeopleResource.addNullable(b, "error", r.error());
        if (r.renewalTokenId() == null) b.addNull("renewalTokenId"); else b.add("renewalTokenId", r.renewalTokenId());
        AdminPeopleResource.addNullable(b, "sentAt", r.sentAt() == null ? null : r.sentAt().toString());
        return b.build();
    }

    // ---- helpers ------------------------------------------------------------

    /** Append the footer with a blank line, if present; the composed body is what gets snapshotted. */
    static String compose(String body, String footer) {
        if (footer == null || footer.isBlank()) return body;
        return body + "\n\n" + footer;
    }

    /** A raw (un-trimmed) string field — email bodies keep their leading/trailing whitespace. */
    private static String rawString(JsonObject o, String key) {
        if (!o.containsKey(key) || o.isNull(key)) return null;
        try {
            return o.getString(key);
        } catch (Exception e) {
            return null;
        }
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(java.util.Locale.ROOT);
    }

    private static String trimName(String given, String family) {
        return ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
    }

    private static String whom(SecurityContext security) {
        return security.getUserPrincipal() instanceof UserPrincipal user ? user.getUsername() : "admin";
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Json.createObjectBuilder().add("error", message).build().toString()).build();
    }

    private static Response conflict(String message) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Json.createObjectBuilder().add("error", message).build().toString()).build();
    }

    private static Response notFound(String message) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Json.createObjectBuilder().add("error", message).build().toString()).build();
    }
}
