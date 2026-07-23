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

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The public-application staging pair (CR-007) — the one deletable people
 * store in the app, deliberately outside the register (see V10). Lifecycle:
 * RECEIVED (awaiting the email round trip) → CONFIRMED (in the admin queue)
 * → APPROVED/REJECTED (terminal, decision recorded). Confirmation tokens
 * follow the CR-004 recipe: 256 random bits, base64url in the link, sha256
 * hex in the row, unknown and expired indistinguishable. Write methods take
 * the caller's {@link Handle} (the CR-003 rule); reads open their own.
 */
final class ApplicationStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** How long an unconfirmed application's link lives; expiry is the only gate. */
    static final int CONFIRM_DAYS = 7;

    record Applicant(int position, String givenName, String familyName,
                     String email, String phone, String relationship) {}

    /** A possible existing-register identity for an applicant (soft flag, never a block). */
    record Match(long personId, String name, Long householdId, boolean hasCurrentMembership) {}

    record Application(long id, String status, OffsetDateTime submittedAt, OffsetDateTime confirmedAt,
                       long membershipTypeId, String typeName,
                       String addressLine1, String addressLine2, String locality, String state,
                       String postcode, String message,
                       LocalDate decisionDate, String minuteReference, String rejectionReason,
                       String decidedBy, Long createdHouseholdId, Long createdMembershipId,
                       String membershipStatus, Integer daysSinceDecision,
                       List<Applicant> applicants) {}

    /** A fresh submission: the raw token exists only here and in the email built from it. */
    record Submitted(long applicationId, String token) {}

    record ConfirmOutcome(long applicationId, boolean firstConfirmation) {}

    private static final String COLS =
            "a.application_id, a.status, a.submitted_at, a.confirmed_at,"
            + " a.membership_type_id, mt.name AS type_name,"
            + " a.address_line_1, a.address_line_2, a.locality, a.state, a.postcode,"
            + " a.applicant_message, a.decision_date, a.minute_reference, a.rejection_reason,"
            + " a.decided_by, a.created_household_id, a.created_membership_id,"
            + " m.status AS membership_status,"
            + " (current_date - a.decision_date) AS days_since_decision";

    private static final String FROM =
            " FROM membership_application a"
            + " JOIN membership_type mt ON mt.membership_type_id = a.membership_type_id"
            + " LEFT JOIN membership m ON m.membership_id = a.created_membership_id";

    private final Jdbi jdbi;

    ApplicationStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    // ---- guest writes -------------------------------------------------------

    Submitted create(Handle handle, long typeId, String line1, String line2, String locality,
                     String state, String postcode, String message, String ip,
                     List<Applicant> applicants) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long id = handle.createUpdate(
                "INSERT INTO membership_application (confirm_token_hash, confirm_expires_at,"
                + " membership_type_id, address_line_1, address_line_2, locality, state, postcode,"
                + " applicant_message, submitted_ip)"
                + " VALUES (:hash, now() + make_interval(days => :days), :type,"
                + " :l1, :l2, :loc, :state, :pc, :msg, :ip)")
                .bind("hash", RenewalTokenStore.sha256Hex(token)).bind("days", CONFIRM_DAYS)
                .bind("type", typeId).bind("l1", line1).bind("l2", line2).bind("loc", locality)
                .bind("state", state).bind("pc", postcode).bind("msg", message).bind("ip", ip)
                .executeAndReturnGeneratedKeys("application_id").mapTo(Long.class).one();
        for (Applicant p : applicants) {
            handle.createUpdate(
                    "INSERT INTO membership_application_person (application_id, position,"
                    + " given_name, family_name, email, phone, relationship)"
                    + " VALUES (:app, :pos, :given, :family, :email, :phone, :rel)")
                    .bind("app", id).bind("pos", p.position())
                    .bind("given", p.givenName()).bind("family", p.familyName())
                    .bind("email", p.email() == null ? null : p.email().toLowerCase(Locale.ROOT))
                    .bind("phone", p.phone()).bind("rel", p.relationship())
                    .execute();
        }
        return new Submitted(id, token);
    }

    /**
     * Resolve a presented confirmation token; empty for unknown AND expired
     * alike (both 404, no oracle). First confirmation flips RECEIVED →
     * CONFIRMED; any later presentation of a live token (double click, mail
     * scanner, already decided) is a harmless non-first success.
     */
    Optional<ConfirmOutcome> confirm(Handle handle, String token) {
        record Row(long id, String status) {}
        Optional<Row> row = handle.createQuery(
                "SELECT application_id, status FROM membership_application"
                + " WHERE confirm_token_hash = :hash AND confirm_expires_at > now()")
                .bind("hash", RenewalTokenStore.sha256Hex(token))
                .map((rs, ctx) -> new Row(rs.getLong("application_id"), rs.getString("status")))
                .findOne();
        if (row.isEmpty()) return Optional.empty();
        if (!"RECEIVED".equals(row.get().status())) {
            return Optional.of(new ConfirmOutcome(row.get().id(), false));
        }
        handle.createUpdate("UPDATE membership_application SET status = 'CONFIRMED',"
                + " confirmed_at = now() WHERE application_id = :id")
                .bind("id", row.get().id()).execute();
        return Optional.of(new ConfirmOutcome(row.get().id(), true));
    }

    // ---- admin reads --------------------------------------------------------

    List<Application> list(String status) {
        return jdbi.withHandle(handle -> {
            String where = status != null ? " WHERE a.status = :status" : "";
            var q = handle.createQuery("SELECT " + COLS + FROM + where
                    + " ORDER BY a.application_id DESC");
            if (status != null) q.bind("status", status);
            List<Application> bases = q.map((rs, ctx) -> mapApplication(rs)).list();
            return attachApplicants(handle, bases);
        });
    }

    Optional<Application> get(long id) {
        return jdbi.withHandle(handle -> find(handle, id));
    }

    Optional<Application> find(Handle handle, long id) {
        return handle.createQuery("SELECT " + COLS + FROM + " WHERE a.application_id = :id")
                .bind("id", id)
                .map((rs, ctx) -> mapApplication(rs))
                .findOne()
                .map(a -> attachApplicants(handle, List.of(a)).get(0));
    }

    /**
     * Load an application's row locked FOR UPDATE — approve/reject serialise
     * on it so a double-click can't decide (or materialise) twice.
     */
    Optional<String> lockStatus(Handle handle, long id) {
        return handle.createQuery("SELECT status FROM membership_application"
                + " WHERE application_id = :id FOR UPDATE")
                .bind("id", id).mapTo(String.class).findOne();
    }

    /**
     * Existing-register identities an applicant might already be (the
     * ImportService.findExisting recipe: case-insensitive email, else
     * given+family name). Informational only — the screen shows them loudly,
     * the server never blocks on them (the CR-013 soft-guard posture).
     */
    List<Match> matches(Handle handle, Applicant applicant) {
        String email = applicant.email() == null ? "" : applicant.email().toLowerCase(Locale.ROOT);
        return handle.createQuery(
                "SELECT p.person_id, trim(p.given_name || ' ' || p.family_name) AS name,"
                + " hh.household_id,"
                + " EXISTS (SELECT 1 FROM membership m"
                + "   JOIN membership_period per ON per.membership_period_id = m.membership_period_id"
                + "   WHERE m.household_id = hh.household_id"
                + "   AND m.status IN ('ACTIVE', 'PENDING_PAYMENT')"
                + "   AND per.end_date >= current_date) AS has_current"
                + " FROM person p"
                + " LEFT JOIN LATERAL (SELECT household_id FROM household_person hp"
                + "   WHERE hp.person_id = p.person_id AND hp.left_household_date IS NULL"
                + "   ORDER BY hp.household_person_id LIMIT 1) hh ON true"
                + " WHERE (lower(p.given_name) = lower(:given) AND lower(p.family_name) = lower(:family))"
                + "   OR EXISTS (SELECT 1 FROM email_address e"
                + "              WHERE e.person_id = p.person_id AND e.email = :email)"
                + " ORDER BY p.person_id LIMIT 10")
                .bind("given", applicant.givenName()).bind("family", applicant.familyName())
                .bind("email", email)
                .map((rs, ctx) -> new Match(rs.getLong("person_id"), rs.getString("name"),
                        (Long) rs.getObject("household_id"), rs.getBoolean("has_current")))
                .list();
    }

    // ---- admin writes -------------------------------------------------------

    void markApproved(Handle handle, long id, LocalDate decisionDate, String minuteReference,
                      String decidedBy, long householdId, long membershipId) {
        handle.createUpdate("UPDATE membership_application SET status = 'APPROVED',"
                + " decision_date = :d, minute_reference = :minute, decided_by = :by,"
                + " created_household_id = :hh, created_membership_id = :m"
                + " WHERE application_id = :id")
                .bind("id", id).bind("d", decisionDate).bind("minute", minuteReference)
                .bind("by", decidedBy).bind("hh", householdId).bind("m", membershipId)
                .execute();
    }

    void markRejected(Handle handle, long id, LocalDate decisionDate, String minuteReference,
                      String reason, String decidedBy) {
        handle.createUpdate("UPDATE membership_application SET status = 'REJECTED',"
                + " decision_date = :d, minute_reference = :minute, rejection_reason = :reason,"
                + " decided_by = :by WHERE application_id = :id")
                .bind("id", id).bind("d", decisionDate).bind("minute", minuteReference)
                .bind("reason", reason).bind("by", decidedBy)
                .execute();
    }

    /** Delete a not-yet-decided row (junk). The CASCADE takes the people with it. */
    void delete(Handle handle, long id) {
        handle.createUpdate("DELETE FROM membership_application WHERE application_id = :id")
                .bind("id", id).execute();
    }

    // ---- mapping ------------------------------------------------------------

    private static List<Application> attachApplicants(Handle handle, List<Application> apps) {
        if (apps.isEmpty()) return apps;
        List<Long> ids = apps.stream().map(Application::id).toList();
        Map<Long, List<Applicant>> byApp = new HashMap<>();
        handle.createQuery("SELECT application_id, position, given_name, family_name,"
                + " email, phone, relationship FROM membership_application_person"
                + " WHERE application_id IN (<ids>) ORDER BY position")
                .bindList("ids", ids)
                .map((rs, ctx) -> Map.entry(rs.getLong("application_id"),
                        new Applicant(rs.getInt("position"), rs.getString("given_name"),
                                rs.getString("family_name"), rs.getString("email"),
                                rs.getString("phone"), rs.getString("relationship"))))
                .forEach(e -> byApp.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return apps.stream().map(a -> new Application(a.id(), a.status(), a.submittedAt(),
                a.confirmedAt(), a.membershipTypeId(), a.typeName(), a.addressLine1(),
                a.addressLine2(), a.locality(), a.state(), a.postcode(), a.message(),
                a.decisionDate(), a.minuteReference(), a.rejectionReason(), a.decidedBy(),
                a.createdHouseholdId(), a.createdMembershipId(), a.membershipStatus(),
                a.daysSinceDecision(), byApp.getOrDefault(a.id(), List.of()))).toList();
    }

    private static Application mapApplication(ResultSet rs) {
        try {
            return new Application(rs.getLong("application_id"), rs.getString("status"),
                    rs.getObject("submitted_at", OffsetDateTime.class),
                    rs.getObject("confirmed_at", OffsetDateTime.class),
                    rs.getLong("membership_type_id"), rs.getString("type_name"),
                    rs.getString("address_line_1"), rs.getString("address_line_2"),
                    rs.getString("locality"), rs.getString("state"), rs.getString("postcode"),
                    rs.getString("applicant_message"),
                    rs.getDate("decision_date") == null ? null : rs.getDate("decision_date").toLocalDate(),
                    rs.getString("minute_reference"), rs.getString("rejection_reason"),
                    rs.getString("decided_by"),
                    (Long) rs.getObject("created_household_id"),
                    (Long) rs.getObject("created_membership_id"),
                    rs.getString("membership_status"),
                    (Integer) rs.getObject("days_since_decision"),
                    List.of());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
