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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import org.jdbi.v3.core.Jdbi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The application queue (CR-007): review, approve, reject, and delete junk —
 * plus the form's settings blob. Approve/reject RECORD a committee decision
 * already taken (clause 3: decision date + optional minute reference), never
 * make one; both require {@link Mail#enabled()} because the notice is the
 * clause 3(5)(a) deliverable — the decision is not recorded without the means
 * to give it. Approval is the only door from staging into the register: one
 * transaction through the CR-010 composite path (every failure thrown, never
 * an early-return Response), materialising straight to PENDING_PAYMENT with
 * the CR-004 pay link in the approval notice. A decided application is the
 * society's record and is never deletable.
 */
@Path("admin/applications")
@RolesAllowed("admin")
public class AdminApplicationsResource {

    private static final Set<String> STATUSES = Set.of("RECEIVED", "CONFIRMED", "APPROVED", "REJECTED");

    private final Jdbi jdbi = Db.jdbi();
    private final ApplicationStore applications = new ApplicationStore(jdbi);
    private final PersonStore people = new PersonStore(jdbi);
    private final HouseholdStore households = new HouseholdStore(jdbi);
    private final MembershipStore memberships = new MembershipStore(jdbi);

    // ---- queue reads --------------------------------------------------------

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("status") String status) {
        if (status != null && !STATUSES.contains(status)) {
            return badRequest("status must be one of " + STATUSES);
        }
        List<ApplicationStore.Application> apps = applications.list(status);
        JsonArrayBuilder rows = Json.createArrayBuilder();
        jdbi.useHandle(handle -> {
            for (ApplicationStore.Application a : apps) {
                rows.add(applicationJson(handle, a));
            }
        });
        return Response.ok(Json.createObjectBuilder().add("applications", rows)
                .build().toString()).build();
    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("id") long id) {
        return applications.get(id)
                .map(a -> Response.ok(jdbi.withHandle(handle ->
                        applicationJson(handle, a)).toString()).build())
                .orElseGet(() -> notFound("no such application"));
    }

    // ---- decisions ----------------------------------------------------------

    @POST
    @Path("{id}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response approve(@PathParam("id") long id, String body, @Context SecurityContext security) {
        if (!Mail.enabled()) return mailDown();
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        String decidedBy = security.getUserPrincipal() instanceof UserPrincipal user
                ? user.getUsername() : "admin";

        long periodId;
        long typeId;
        LocalDate decisionDate;
        String minuteReference;
        String householdName;
        try {
            periodId = Payloads.reqLong(request, "membershipPeriodId");
            typeId = Payloads.reqLong(request, "membershipTypeId");
            decisionDate = Payloads.reqDate(request, "decisionDate");
            minuteReference = Payloads.optString(request, "minuteReference");
            householdName = Payloads.optString(request, "householdName");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        record Approved(JsonObject response, String noticeTo, String noticeBody) {}
        final long finalPeriodId = periodId;
        final long finalTypeId = typeId;
        final LocalDate finalDecisionDate = decisionDate;
        final String finalMinute = minuteReference;
        final String finalHouseholdName = householdName;
        Approved approved;
        try {
            approved = jdbi.inTransaction(handle -> {
                String status = applications.lockStatus(handle, id)
                        .orElseThrow(() -> new NotFoundException());
                if (!"CONFIRMED".equals(status)) {
                    throw new ConflictException("application is " + status
                            + " — only a CONFIRMED application can be decided");
                }
                LocalDate today = handle.createQuery("SELECT current_date")
                        .mapTo(LocalDate.class).one();
                if (finalDecisionDate.isAfter(today)) {
                    throw new IllegalArgumentException("decisionDate cannot be in the future"
                            + " — it records a decision the committee has already made");
                }
                ApplicationStore.Application app = applications.find(handle, id).orElseThrow();

                // the CR-010 sequence, with the application's people
                MembershipStore.TypeBounds bounds = MembershipStore.typeBounds(handle, finalTypeId)
                        .orElseThrow(() -> new IllegalArgumentException("no such membershipTypeId"));
                if (!PeriodStore.exists(handle, finalPeriodId)) {
                    throw new IllegalArgumentException("no such membershipPeriodId");
                }
                long memberCount = app.applicants().stream()
                        .filter(a -> "MEMBER".equals(a.relationship())).count();
                if (bounds.maximumPeople() != null && memberCount > bounds.maximumPeople()) {
                    throw new IllegalArgumentException(bounds.name() + " allows at most "
                            + bounds.maximumPeople()
                            + (bounds.maximumPeople() == 1 ? " formal member" : " formal members")
                            + " (" + memberCount + " applying)");
                }
                boolean underMinimum = bounds.minimumPeople() != null
                        && app.applicants().size() < bounds.minimumPeople();

                List<Long> personIds = new ArrayList<>();
                ApplicationStore.Applicant first = app.applicants().get(0);
                PersonStore.Person p1 = people.create(handle, draftFrom(first));
                personIds.add(p1.id());
                String name = finalHouseholdName != null ? finalHouseholdName
                        : first.familyName() + " household";
                HouseholdStore.Household household = households.create(handle, name, p1.id())
                        .orElseThrow(() -> new IllegalStateException("primary contact just created"));
                for (ApplicationStore.Applicant a : app.applicants()) {
                    if (a.position() == 1) continue;
                    PersonStore.Person p = people.create(handle, draftFrom(a));
                    households.addPerson(handle, household.id(), p.id(), a.relationship());
                    personIds.add(p.id());
                }

                MembershipStore.CreateOutcome outcome =
                        memberships.createForHousehold(handle, household.id(), finalPeriodId,
                                finalTypeId, null);
                // the committee's dates, not the click's: application_date is the
                // submission, approved_date the recorded decision — recompute
                // preserves it (COALESCE) when payment later activates
                handle.createUpdate("UPDATE membership SET application_date = :app,"
                        + " approved_date = :approved WHERE membership_id = :id")
                        .bind("app", app.submittedAt().toLocalDate())
                        .bind("approved", finalDecisionDate)
                        .bind("id", outcome.membershipId()).execute();

                if (app.addressLine1() != null) {
                    households.addPostalAddress(handle, household.id(), app.addressLine1(),
                            app.addressLine2(), app.locality(), app.state(), app.postcode());
                }

                String payUrl = null;
                if (outcome.amountDueCents() > 0) {
                    payUrl = PayResource.payUrl(RenewalTokenStore
                            .mint(handle, outcome.membershipId()).orElseThrow().token());
                }

                applications.markApproved(handle, id, finalDecisionDate, finalMinute, decidedBy,
                        household.id(), outcome.membershipId());

                JsonArrayBuilder warnings = Json.createArrayBuilder();
                if (underMinimum) {
                    warnings.add(bounds.name() + " normally has at least " + bounds.minimumPeople()
                            + " people — add the second person via household detail");
                }
                if (outcome.lateJoiningWarning()) {
                    warnings.add("today is past the period's late-joining cutoff — consider the next period");
                }
                JsonArrayBuilder ids = Json.createArrayBuilder();
                personIds.forEach(ids::add);
                JsonObject response = Json.createObjectBuilder()
                        .add("householdId", household.id())
                        .add("membershipId", outcome.membershipId())
                        .add("personIds", ids)
                        .add("status", outcome.status())
                        .add("amountDueCents", outcome.amountDueCents())
                        .add("warnings", warnings)
                        .build();
                return new Approved(response, first.email(),
                        approvalNotice(first.givenName(), finalDecisionDate, bounds.name(),
                                outcome.amountDueCents(), payUrl));
            });
        } catch (NotFoundException e) {
            return notFound("no such application");
        } catch (ConflictException e) {
            return conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        // clause 3(5)(a): the approval notice, composed from committed state
        // (the CR-012 webhook pattern) and carrying the 28-day wording
        Mail.sendAsync(approved.noticeTo(),
                Mail.societyName() + " — your membership application is approved",
                approved.noticeBody());
        return Response.ok(approved.response().toString()).build();
    }

    /** An applicant as a register person draft: email primary, phone untyped-primary. */
    private static PersonStore.Person draftFrom(ApplicationStore.Applicant a) {
        List<PersonStore.Email> emails = a.email() == null ? List.of()
                : List.of(new PersonStore.Email(a.email(), true));
        List<PersonStore.Phone> phones = a.phone() == null ? List.of()
                : List.of(new PersonStore.Phone(a.phone(), null, true));
        return new PersonStore.Person(0, null, a.givenName(), a.familyName(),
                null, null, null, null, null, emails, phones);
    }

    private static String approvalNotice(String givenName, LocalDate decisionDate,
                                         String typeName, int dueCents, String payUrl) {
        String society = Mail.societyName();
        StringBuilder text = new StringBuilder("Dear ").append(givenName).append(",\n\n")
                .append("We are pleased to let you know the committee of ").append(society)
                .append(" approved your membership application on ").append(decisionDate)
                .append(".\n\nMembership type: ").append(typeName).append('\n');
        if (payUrl != null) {
            text.append("Amount due: ").append(PayResource.dollars(dueCents)).append("\n\n")
                    .append("Under the society's constitution, membership begins once payment")
                    .append(" is received. Please pay within 28 days using this secure link:\n")
                    .append(payUrl).append("\n\n")
                    .append("If you'd rather pay another way, just contact the society.\n");
        } else {
            text.append("\nNo payment is required — your membership is now active.\n");
        }
        text.append("\nWelcome!\n\n").append(society).append('\n');
        return text.toString();
    }

    @POST
    @Path("{id}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response reject(@PathParam("id") long id, String body, @Context SecurityContext security) {
        if (!Mail.enabled()) return mailDown();
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        String decidedBy = security.getUserPrincipal() instanceof UserPrincipal user
                ? user.getUsername() : "admin";

        LocalDate decisionDate;
        String minuteReference;
        String reason;
        try {
            decisionDate = Payloads.reqDate(request, "decisionDate");
            minuteReference = Payloads.optString(request, "minuteReference");
            reason = Payloads.optString(request, "reason");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        final LocalDate finalDecisionDate = decisionDate;
        final String finalMinute = minuteReference;
        final String finalReason = reason;
        String noticeTo;
        String givenName;
        try {
            record Rejected(String email, String givenName) {}
            Rejected r = jdbi.inTransaction(handle -> {
                String status = applications.lockStatus(handle, id)
                        .orElseThrow(() -> new NotFoundException());
                if (!"CONFIRMED".equals(status)) {
                    throw new ConflictException("application is " + status
                            + " — only a CONFIRMED application can be decided");
                }
                LocalDate today = handle.createQuery("SELECT current_date")
                        .mapTo(LocalDate.class).one();
                if (finalDecisionDate.isAfter(today)) {
                    throw new IllegalArgumentException("decisionDate cannot be in the future"
                            + " — it records a decision the committee has already made");
                }
                ApplicationStore.Application app = applications.find(handle, id).orElseThrow();
                applications.markRejected(handle, id, finalDecisionDate, finalMinute,
                        finalReason, decidedBy);
                ApplicationStore.Applicant first = app.applicants().get(0);
                return new Rejected(first.email(), first.givenName());
            });
            noticeTo = r.email();
            givenName = r.givenName();
        } catch (NotFoundException e) {
            return notFound("no such application");
        } catch (ConflictException e) {
            return conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        // a neutral template on purpose: the clause requires notice of the
        // decision, not grounds — the stored reason stays internal
        String society = Mail.societyName();
        Mail.sendAsync(noticeTo, society + " — your membership application",
                "Dear " + givenName + ",\n\n"
                + "The committee of " + society + " considered your membership application on "
                + decisionDate + " and regrets that it was not approved.\n\n"
                + "If you have any questions, please contact the society.\n\n"
                + society + "\n");
        return Response.ok(Json.createObjectBuilder().add("status", "REJECTED")
                .build().toString()).build();
    }

    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") long id) {
        try {
            jdbi.useTransaction(handle -> {
                String status = applications.lockStatus(handle, id)
                        .orElseThrow(() -> new NotFoundException());
                if ("APPROVED".equals(status) || "REJECTED".equals(status)) {
                    throw new ConflictException("a decided application is the society's record"
                            + " of a committee decision and cannot be deleted");
                }
                applications.delete(handle, id);
            });
        } catch (NotFoundException e) {
            return notFound("no such application");
        } catch (ConflictException e) {
            return conflict(e.getMessage());
        }
        return Response.noContent().build();
    }

    // ---- settings -----------------------------------------------------------

    @GET
    @Path("settings")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSettings() {
        ApplicationSettings.Settings settings = jdbi.withHandle(ApplicationSettings::read);
        return Response.ok(settingsJson(settings)).build();
    }

    @PUT
    @Path("settings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response putSettings(String body, @Context SecurityContext security) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        String alertMailbox = Payloads.optString(request, "alertMailbox");
        if (alertMailbox != null && !alertMailbox.contains("@")) {
            return badRequest("alertMailbox must be an email address");
        }
        boolean formEnabled = Payloads.optBool(request, "formEnabled", false);
        String updatedBy = security.getUserPrincipal() instanceof UserPrincipal user
                ? user.getUsername() : "admin";
        JsonObjectBuilder blob = Json.createObjectBuilder().add("formEnabled", formEnabled);
        if (alertMailbox != null) blob.add("alertMailbox", alertMailbox);
        String value = blob.build().toString();
        jdbi.useHandle(handle -> handle.createUpdate(
                "INSERT INTO app_setting (key, value, updated_by) VALUES (:k, :v, :by)"
                + " ON CONFLICT (key) DO UPDATE SET value = :v, updated_by = :by, updated_at = now()")
                .bind("k", ApplicationSettings.SETTINGS_KEY).bind("v", value).bind("by", updatedBy)
                .execute());
        return Response.ok(settingsJson(new ApplicationSettings.Settings(alertMailbox, formEnabled)))
                .build();
    }

    private static String settingsJson(ApplicationSettings.Settings settings) {
        JsonObjectBuilder b = Json.createObjectBuilder().add("formEnabled", settings.formEnabled());
        AdminPeopleResource.addNullable(b, "alertMailbox", settings.alertMailbox());
        b.add("mailEnabled", Mail.enabled());
        return b.build().toString();
    }

    // ---- rendering ----------------------------------------------------------

    private JsonObject applicationJson(org.jdbi.v3.core.Handle handle,
                                       ApplicationStore.Application a) {
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("id", a.id())
                .add("status", a.status())
                .add("submittedAt", a.submittedAt().toString())
                .add("membershipTypeId", a.membershipTypeId())
                .add("typeName", a.typeName());
        AdminPeopleResource.addNullable(b, "confirmedAt",
                a.confirmedAt() == null ? null : a.confirmedAt().toString());
        AdminPeopleResource.addNullable(b, "addressLine1", a.addressLine1());
        AdminPeopleResource.addNullable(b, "addressLine2", a.addressLine2());
        AdminPeopleResource.addNullable(b, "locality", a.locality());
        AdminPeopleResource.addNullable(b, "state", a.state());
        AdminPeopleResource.addNullable(b, "postcode", a.postcode());
        AdminPeopleResource.addNullable(b, "message", a.message());
        AdminPeopleResource.addNullable(b, "decisionDate",
                a.decisionDate() == null ? null : a.decisionDate().toString());
        AdminPeopleResource.addNullable(b, "minuteReference", a.minuteReference());
        AdminPeopleResource.addNullable(b, "rejectionReason", a.rejectionReason());
        AdminPeopleResource.addNullable(b, "decidedBy", a.decidedBy());
        if (a.createdHouseholdId() == null) b.addNull("createdHouseholdId");
        else b.add("createdHouseholdId", a.createdHouseholdId());
        if (a.createdMembershipId() == null) b.addNull("createdMembershipId");
        else b.add("createdMembershipId", a.createdMembershipId());
        // the 28-day aging view (clause 3(5)(b)): paid + days since the decision
        if ("APPROVED".equals(a.status())) {
            b.add("paid", "ACTIVE".equals(a.membershipStatus()));
            AdminPeopleResource.addNullable(b, "daysSinceDecision", a.daysSinceDecision());
        }
        JsonArrayBuilder applicants = Json.createArrayBuilder();
        boolean undecided = "RECEIVED".equals(a.status()) || "CONFIRMED".equals(a.status());
        for (ApplicationStore.Applicant p : a.applicants()) {
            JsonObjectBuilder pj = Json.createObjectBuilder()
                    .add("position", p.position())
                    .add("givenName", p.givenName())
                    .add("familyName", p.familyName())
                    .add("relationship", p.relationship());
            AdminPeopleResource.addNullable(pj, "email", p.email());
            AdminPeopleResource.addNullable(pj, "phone", p.phone());
            if (undecided) {
                JsonArrayBuilder matches = Json.createArrayBuilder();
                for (ApplicationStore.Match m : applications.matches(handle, p)) {
                    JsonObjectBuilder mj = Json.createObjectBuilder()
                            .add("personId", m.personId())
                            .add("name", m.name())
                            .add("hasCurrentMembership", m.hasCurrentMembership());
                    if (m.householdId() == null) mj.addNull("householdId");
                    else mj.add("householdId", m.householdId());
                    matches.add(mj);
                }
                pj.add("matches", matches);
            }
            applicants.add(pj);
        }
        b.add("applicants", applicants);
        return b.build();
    }

    // ---- responses ----------------------------------------------------------

    /** Internal control flow for "no such application" inside a transaction. */
    private static final class NotFoundException extends RuntimeException {}

    private static Response mailDown() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(errorJson("mail is not configured — the decision notice is the"
                        + " constitution's deliverable, so decisions cannot be recorded"
                        + " until the mail settings page is set up")).build();
    }

    private static Response notFound(String message) {
        return Response.status(Response.Status.NOT_FOUND).entity(errorJson(message)).build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST).entity(errorJson(message)).build();
    }

    private static Response conflict(String message) {
        return Response.status(Response.Status.CONFLICT).entity(errorJson(message)).build();
    }

    private static String errorJson(String message) {
        return Json.createObjectBuilder().add("error", message).build().toString();
    }
}
