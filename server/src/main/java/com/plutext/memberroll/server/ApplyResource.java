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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The public application form's API (CR-007), guest-reachable by design —
 * an applicant is by definition not yet anyone the system knows. Nothing
 * here touches the register: a submission lands in the V10 staging pair as
 * RECEIVED, and only the email-confirmation round trip (the CR-006
 * mailbox-control trust anchor) moves it into the admin queue. Spam posture
 * without external dependencies: a honeypot field (silently accepted and
 * dropped), the CR-004 per-address cooldown, and a coarse global
 * submissions-per-hour backstop — the round trip is the real gate, junk
 * that never confirms is invisible to the queue's default view.
 *
 * The whole flow requires mail ({@code formEnabled} AND {@link
 * Mail#enabled()}): without a relay there is no round trip, so submission
 * answers 503 (the checkout-disabled convention) rather than accepting
 * applications it can never confirm.
 */
@Path("apply")
public class ApplyResource {

    private static final Logger LOG = Logger.getLogger(ApplyResource.class.getName());

    private static final Set<String> RELATIONSHIP_TYPES =
            Set.of("MEMBER", "PARTNER", "DEPENDANT", "OTHER");

    // per-address cooldown (the PayResource.LOST_LINK pattern verbatim):
    // bounded so attacker-supplied addresses can't grow it forever
    private static final ConcurrentHashMap<String, Long> RECENT = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 10 * 60 * 1000;

    // global backstop: this society receives single-digit applications a
    // month, so anything resembling volume is a flood — a global cap catches
    // it without discriminating by IP (CGNAT makes per-IP a false-positive
    // machine at this demographic). 50 also keeps repeated verify-matrix
    // runs (~8 submissions each) green within one window.
    private static final int HOURLY_CAP = 50;
    private static final Object CAP_LOCK = new Object();
    private static long capWindowStart;
    private static int capCount;

    private final Jdbi jdbi = Db.jdbi();
    private final ApplicationStore applications = new ApplicationStore(jdbi);

    // ---- form data ----------------------------------------------------------

    @GET
    @Path("options")
    @Produces(MediaType.APPLICATION_JSON)
    public Response options() {
        return jdbi.withHandle(handle -> {
            ApplicationSettings.Settings settings = ApplicationSettings.read(handle);
            List<PeriodStore.Price> types = offeredTypes(handle);
            boolean open = settings.formEnabled() && Mail.enabled() && !types.isEmpty();
            JsonArrayBuilder typesJson = Json.createArrayBuilder();
            for (PeriodStore.Price t : types) {
                var b = Json.createObjectBuilder()
                        .add("id", t.typeId())
                        .add("name", t.typeName())
                        .add("priceCents", t.amountCents());
                AdminPeopleResource.addNullable(b, "minimumPeople", t.minimumPeople());
                AdminPeopleResource.addNullable(b, "maximumPeople", t.maximumPeople());
                typesJson.add(b);
            }
            return Response.ok(Json.createObjectBuilder()
                    .add("open", open)
                    .add("societyName", Mail.societyName())
                    .add("types", typesJson)
                    .build().toString()).build();
        });
    }

    /**
     * The types the public may apply for: the positively-priced types of the
     * period covering today (else the next-starting one). A $0 type (LIFE) is
     * never publicly applicable. Display-only — the authority is the admin's
     * period/type choice at approval.
     */
    private static List<PeriodStore.Price> offeredTypes(Handle handle) {
        Optional<Long> periodId = handle.createQuery(
                "SELECT membership_period_id FROM membership_period"
                + " WHERE end_date > current_date"
                + " ORDER BY start_date, membership_period_id LIMIT 1")
                .mapTo(Long.class).findOne();
        if (periodId.isEmpty()) return List.of();
        return handle.createQuery(
                "SELECT tp.membership_type_id, mt.name, tp.amount_cents,"
                + " mt.minimum_people, mt.maximum_people"
                + " FROM membership_type_price tp JOIN membership_type mt"
                + "   ON mt.membership_type_id = tp.membership_type_id"
                + " WHERE tp.membership_period_id = :period AND tp.amount_cents > 0"
                + " ORDER BY mt.name")
                .bind("period", periodId.get())
                .map((rs, ctx) -> new PeriodStore.Price(rs.getLong("membership_type_id"),
                        rs.getString("name"), rs.getInt("amount_cents"),
                        (Integer) rs.getObject("minimum_people"),
                        (Integer) rs.getObject("maximum_people")))
                .list();
    }

    // ---- submission ---------------------------------------------------------

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response submit(String body, @Context HttpServletRequest servletRequest) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");

        // honeypot first: a filled hidden field gets the success a bot expects
        // and writes nothing — before validation, so junk can't probe the rules
        if (Payloads.optString(request, "website") != null) {
            LOG.info("apply: honeypot tripped — dropping submission");
            return accepted();
        }

        List<ApplicationStore.Applicant> applicants;
        Long typeId;
        String line1;
        String line2;
        String locality;
        String state;
        String postcode;
        String message;
        try {
            typeId = Payloads.reqLong(request, "membershipTypeId");
            applicants = parseApplicants(request);
            line1 = Payloads.optString(request, "addressLine1");
            line2 = Payloads.optString(request, "addressLine2");
            locality = Payloads.optString(request, "locality");
            state = Payloads.optString(request, "state");
            postcode = Payloads.optString(request, "postcode");
            if (line1 == null && (line2 != null || locality != null || state != null || postcode != null)) {
                throw new IllegalArgumentException("addressLine1 is required when giving an address");
            }
            message = Payloads.optString(request, "message");
            if (message != null && message.length() > 2000) {
                throw new IllegalArgumentException("message is too long (2000 characters max)");
            }
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        String email = applicants.get(0).email();
        if (underCooldown(email)) return tooMany("please wait a few minutes before trying again");
        if (overHourlyCap()) return tooMany("too many applications right now — please try again later");

        final long finalTypeId = typeId;
        final List<ApplicationStore.Applicant> finalApplicants = applicants;
        final String l1 = line1;
        final String l2 = line2;
        final String loc = locality;
        final String st = state;
        final String pc = postcode;
        final String msg = message;
        String ip = servletRequest != null ? servletRequest.getRemoteAddr() : null;
        ApplicationStore.Submitted submitted;
        try {
            submitted = jdbi.inTransaction(handle -> {
                ApplicationSettings.Settings settings = ApplicationSettings.read(handle);
                if (!settings.formEnabled() || !Mail.enabled()) {
                    throw new ServiceClosedException();
                }
                List<PeriodStore.Price> offered = offeredTypes(handle);
                PeriodStore.Price type = offered.stream()
                        .filter(t -> t.typeId() == finalTypeId).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "membershipTypeId is not one of the offered types"));
                long memberCount = finalApplicants.stream()
                        .filter(a -> "MEMBER".equals(a.relationship())).count();
                if (type.maximumPeople() != null && memberCount > type.maximumPeople()) {
                    throw new IllegalArgumentException(type.typeName() + " covers at most "
                            + type.maximumPeople()
                            + (type.maximumPeople() == 1 ? " applicant" : " applicants")
                            + " — choose a household type, or mark the second person as a partner");
                }
                return applications.create(handle, finalTypeId, l1, l2, loc, st, pc, msg, ip,
                        finalApplicants);
            });
        } catch (ServiceClosedException e) {
            return closed();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        String confirmUrl = PayResource.publicBaseUrl() + "/web/apply.html?confirm=" + submitted.token();
        String society = Mail.societyName();
        Mail.sendAsync(email, society + " — confirm your membership application",
                "Hello " + applicants.get(0).givenName() + ",\n\n"
                + "We received an application for membership of " + society + ".\n\n"
                + "To confirm it and send it to the committee, open this link:\n"
                + confirmUrl + "\n\n"
                + "The link is valid for " + ApplicationStore.CONFIRM_DAYS + " days."
                + " If you did not apply, you can ignore this email — nothing further"
                + " happens without confirmation.\n\n"
                + society + "\n");
        return accepted();
    }

    private static List<ApplicationStore.Applicant> parseApplicants(JsonObject request) {
        String given = Payloads.optString(request, "givenName");
        String family = Payloads.optString(request, "familyName");
        String email = Payloads.optString(request, "email");
        if (given == null || family == null) {
            throw new IllegalArgumentException("givenName and familyName are required");
        }
        if (email == null || !email.contains("@")) {
            throw new IllegalArgumentException("a valid email address is required"
                    + " — we confirm applications by email");
        }
        List<ApplicationStore.Applicant> applicants = new ArrayList<>();
        applicants.add(new ApplicationStore.Applicant(1, given, family,
                email.toLowerCase(Locale.ROOT), Payloads.optString(request, "phone"), "MEMBER"));
        if (request.containsKey("secondPerson") && !request.isNull("secondPerson")) {
            JsonObject sp = request.getJsonObject("secondPerson");
            String given2 = Payloads.optString(sp, "givenName");
            String family2 = Payloads.optString(sp, "familyName");
            if (given2 == null || family2 == null) {
                throw new IllegalArgumentException("secondPerson.givenName and familyName are required");
            }
            String email2 = Payloads.optString(sp, "email");
            if (email2 != null && !email2.contains("@")) {
                throw new IllegalArgumentException("secondPerson.email must contain @");
            }
            String rel = Payloads.optString(sp, "relationship");
            if (rel == null) rel = "PARTNER";
            if (!RELATIONSHIP_TYPES.contains(rel)) {
                throw new IllegalArgumentException("secondPerson.relationship must be one of "
                        + RELATIONSHIP_TYPES);
            }
            applicants.add(new ApplicationStore.Applicant(2, given2, family2,
                    email2 == null ? null : email2.toLowerCase(Locale.ROOT),
                    Payloads.optString(sp, "phone"), rel));
        }
        return applicants;
    }

    // ---- confirmation -------------------------------------------------------

    @POST
    @Path("confirm")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response confirm(String body) {
        JsonObject request = Payloads.read(body);
        String token = request == null ? null : Payloads.optString(request, "token");
        if (token == null) return badRequest("token is required");

        record Confirmed(ApplicationStore.ConfirmOutcome outcome, ApplicationSettings.Settings settings,
                         ApplicationStore.Application application) {}
        Confirmed result = jdbi.inTransaction(handle ->
                applications.confirm(handle, token)
                        .map(o -> new Confirmed(o, ApplicationSettings.read(handle),
                                applications.find(handle, o.applicationId()).orElseThrow()))
                        .orElse(null));
        if (result == null) {
            // deliberately identical for unknown and expired (CR-004 parity)
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(error("link not recognised or expired")).build();
        }
        if (result.outcome().firstConfirmation()) {
            sendAlert(result.settings(), result.application());
        }
        return Response.ok(Json.createObjectBuilder()
                .add("message", "Thank you — your application has been confirmed and"
                        + " will be considered by the committee.")
                .build().toString()).build();
    }

    /**
     * One transactional notice per confirmed application, to the configured
     * alert mailbox, else the current secretary (clause 3(3) — the CR-013
     * contacts seam built for exactly this consumer). Best-effort: the queue
     * shows the application either way.
     */
    private void sendAlert(ApplicationSettings.Settings settings, ApplicationStore.Application app) {
        Mail.async(() -> {
            try {
                String to = settings.alertMailbox();
                if (to == null) {
                    to = new CommitteeStore(jdbi).contacts().stream()
                            .filter(c -> "SECRETARY".equals(c.office()) && c.email() != null)
                            .map(CommitteeStore.Contact::email)
                            .findFirst().orElse(null);
                }
                if (to == null) {
                    LOG.warning("application confirmed but no alert mailbox and no secretary email"
                            + " — application " + app.id() + " waits in the admin queue unannounced");
                    return;
                }
                StringBuilder text = new StringBuilder("A new membership application has been"
                        + " confirmed and is ready for the committee:\n");
                for (ApplicationStore.Applicant a : app.applicants()) {
                    text.append('\n').append(a.givenName()).append(' ').append(a.familyName());
                    if (!"MEMBER".equals(a.relationship())) {
                        text.append(" (").append(a.relationship().toLowerCase(Locale.ROOT)).append(')');
                    }
                    if (a.email() != null) text.append(" — ").append(a.email());
                }
                text.append("\n\nRequested membership type: ").append(app.typeName())
                        .append("\n\nReview it in the admin panel: ")
                        .append(PayResource.publicBaseUrl()).append("/admin/applications.html\n");
                Mail.send(to, Mail.societyName() + " — new membership application", text.toString());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "application alert failed for application " + app.id(), e);
            }
        });
    }

    // ---- rate limiting ------------------------------------------------------

    private static boolean underCooldown(String address) {
        long now = System.currentTimeMillis();
        if (RECENT.size() > 10_000) RECENT.clear();
        Long last = RECENT.putIfAbsent(address, now);
        if (last == null) return false;
        if (now - last < COOLDOWN_MS) return true;
        RECENT.put(address, now);
        return false;
    }

    private static boolean overHourlyCap() {
        synchronized (CAP_LOCK) {
            long now = System.currentTimeMillis();
            if (now - capWindowStart > 60 * 60 * 1000) {
                capWindowStart = now;
                capCount = 0;
            }
            return ++capCount > HOURLY_CAP;
        }
    }

    // ---- responses ----------------------------------------------------------

    /** Thrown inside the submit transaction when the form is off or mail is down. */
    private static final class ServiceClosedException extends RuntimeException {}

    private static Response accepted() {
        return Response.accepted(Json.createObjectBuilder()
                .add("message", "Almost there — check your email for a link to confirm"
                        + " your application.")
                .build().toString()).build();
    }

    private static Response closed() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(error("membership applications are not currently open on this site"
                        + " — please contact the society")).build();
    }

    private static Response tooMany(String message) {
        return Response.status(429).entity(error(message)).build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST).entity(error(message)).build();
    }

    private static String error(String message) {
        return Json.createObjectBuilder().add("error", message).build().toString();
    }
}
