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

import java.util.List;
import java.util.Optional;

/**
 * The SQL behind member self-serve (CR-006): provisioning candidates, the
 * {@code person.keycloak_subject} link column, and the "my membership" view.
 * The link — not the self-claimed {@code member} role — is what authorizes
 * the member endpoints, and every read here re-derives entitlement from
 * CURRENT register state (current MEMBER row, current household), so a
 * person removed from a household loses access with no Keycloak action
 * (CR-006 principle 7).
 */
final class SelfServeStore {

    private SelfServeStore() {}

    // ---- provisioning candidates -------------------------------------------

    /**
     * One person×household provisioning candidate row. A person legitimately
     * appears once per current MEMBER household (the cross-household email
     * conflict rule then fires on their own address).
     */
    record Candidate(long personId, String givenName, String familyName, String email,
                     long householdId, boolean primaryContact, String linkedSubject) {}

    /**
     * Every person with a current MEMBER row in an ACTIVE household, not
     * deceased, with a current email address (primary, else lowest email id).
     * People with no email are simply not candidates — unlike CR-005's
     * NO_EMAIL, which is about send coverage, provisioning has nothing to
     * offer an offline member.
     */
    static List<Candidate> candidates(Handle handle) {
        return handle.createQuery(
                "SELECT hp.person_id, p.given_name, p.family_name, p.keycloak_subject,"
                + " hp.household_id, (h.primary_contact_person_id = hp.person_id) AS primary_contact,"
                + " e.email"
                + " FROM household_person hp"
                + " JOIN person p ON p.person_id = hp.person_id"
                + " JOIN household h ON h.household_id = hp.household_id"
                + " JOIN LATERAL (SELECT email FROM email_address e WHERE e.person_id = p.person_id"
                + "   ORDER BY e.is_primary DESC, e.email_id LIMIT 1) e ON true"
                + " WHERE hp.left_household_date IS NULL AND hp.relationship_type = 'MEMBER'"
                + "   AND h.status = 'ACTIVE' AND p.deceased_date IS NULL"
                + " ORDER BY hp.person_id, hp.household_id")
                .map((rs, ctx) -> new Candidate(rs.getLong("person_id"), rs.getString("given_name"),
                        rs.getString("family_name"), rs.getString("email"), rs.getLong("household_id"),
                        rs.getBoolean("primary_contact"), rs.getString("keycloak_subject")))
                .list();
    }

    // ---- the link column ----------------------------------------------------

    static void link(Handle handle, long personId, String subject) {
        handle.createUpdate("UPDATE person SET keycloak_subject = :sub WHERE person_id = :id")
                .bind("sub", subject).bind("id", personId).execute();
    }

    /** True if a row was actually unlinked (person exists; linked or not). */
    static boolean unlink(Handle handle, long personId) {
        return handle.createUpdate("UPDATE person SET keycloak_subject = NULL WHERE person_id = :id")
                .bind("id", personId).execute() > 0;
    }

    /** The person id a Keycloak subject is linked to, if any. */
    static Optional<Long> personIdLinkedTo(Handle handle, String subject) {
        return handle.createQuery("SELECT person_id FROM person WHERE keycloak_subject = :sub")
                .bind("sub", subject).mapTo(Long.class).findOne();
    }

    record LinkedPerson(long personId, String givenName, String familyName) {}

    static Optional<LinkedPerson> personBySubject(Handle handle, String subject) {
        return handle.createQuery(
                "SELECT person_id, given_name, family_name FROM person WHERE keycloak_subject = :sub")
                .bind("sub", subject)
                .map((rs, ctx) -> new LinkedPerson(rs.getLong("person_id"),
                        rs.getString("given_name"), rs.getString("family_name")))
                .findOne();
    }

    /** Empty for an unknown person AND an unlinked one alike (JDBI findOne folds the NULL). */
    static Optional<String> subjectOf(Handle handle, long personId) {
        return handle.createQuery("SELECT keycloak_subject FROM person WHERE person_id = :id")
                .bind("id", personId).mapTo(String.class).findOne();
    }

    static boolean personExists(Handle handle, long personId) {
        return handle.createQuery("SELECT count(*) FROM person WHERE person_id = :id")
                .bind("id", personId).mapTo(Integer.class).one() > 0;
    }

    // ---- the "my membership" view -------------------------------------------

    /**
     * The window condition shared by the payable set and the pay-link guard:
     * non-CEASED, in a period that is current OR whose renewal window is open
     * — exactly {@link RenewalTokenStore#lostLinkRows}' criteria, so the
     * logged-in view and the emailed magic link agree on what is payable.
     */
    private static final String PAYABLE_SQL =
            "m.status <> 'CEASED' AND current_date <= per.end_date"
            + " AND (current_date >= per.start_date"
            + "      OR (per.renewal_open_date IS NOT NULL AND current_date >= per.renewal_open_date))";

    private static final String MEMBER_HOUSEHOLDS_SQL =
            " FROM household_person hp"
            + " JOIN membership m ON m.household_id = hp.household_id"
            + " JOIN membership_period per ON per.membership_period_id = m.membership_period_id"
            + " JOIN membership_type mt ON mt.membership_type_id = m.membership_type_id"
            + " JOIN household h ON h.household_id = m.household_id"
            + " JOIN person pc ON pc.person_id = h.primary_contact_person_id"
            + " WHERE hp.person_id = :pid AND hp.left_household_date IS NULL"
            + "   AND hp.relationship_type = 'MEMBER'";

    record MembershipRow(long membershipId, String displayName, String periodName, String typeName,
                         String status, int amountDueCents, int amountPaidCents) {}

    /** The linked person's payable/current memberships (their current MEMBER households). */
    static List<MembershipRow> currentMemberships(Handle handle, long personId) {
        return handle.createQuery(
                "SELECT DISTINCT m.membership_id,"
                + " COALESCE(NULLIF(trim(h.household_name), ''),"
                + "          trim(pc.given_name || ' ' || pc.family_name)) AS display_name,"
                + " per.name AS period_name, mt.name AS type_name, m.status, m.amount_due_cents,"
                + " " + MembershipStore.PAID_SQL + " AS paid"
                + MEMBER_HOUSEHOLDS_SQL
                + "   AND " + PAYABLE_SQL
                + " ORDER BY m.membership_id")
                .bind("pid", personId)
                .map((rs, ctx) -> new MembershipRow(rs.getLong("membership_id"),
                        rs.getString("display_name"), rs.getString("period_name"),
                        rs.getString("type_name"), rs.getString("status"),
                        rs.getInt("amount_due_cents"), rs.getInt("paid")))
                .list();
    }

    record HistoryRow(String periodName, String typeName, String status) {}

    /** Past periods' rows — the read-only record ("was I financial last year?"). */
    static List<HistoryRow> history(Handle handle, long personId) {
        return handle.createQuery(
                "SELECT DISTINCT per.name AS period_name, mt.name AS type_name, m.status,"
                + " per.start_date, m.membership_id"
                + MEMBER_HOUSEHOLDS_SQL
                + "   AND NOT (" + PAYABLE_SQL + ")"
                + " ORDER BY per.start_date DESC, m.membership_id DESC")
                .bind("pid", personId)
                .map((rs, ctx) -> new HistoryRow(rs.getString("period_name"),
                        rs.getString("type_name"), rs.getString("status")))
                .list();
    }

    /**
     * The pay-link guard: the membership must be in the caller's payable set
     * (same shape as the GET — a person removed from the household loses pay
     * access with no Keycloak action). False reads as 404 to the caller.
     */
    static boolean canPay(Handle handle, long personId, long membershipId) {
        return handle.createQuery(
                "SELECT count(*)" + MEMBER_HOUSEHOLDS_SQL
                + "   AND m.membership_id = :mid AND " + PAYABLE_SQL)
                .bind("pid", personId).bind("mid", membershipId)
                .mapTo(Integer.class).one() > 0;
    }
}
