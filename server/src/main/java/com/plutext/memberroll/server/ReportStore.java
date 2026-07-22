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

import org.jdbi.v3.core.Jdbi;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only queries behind the CR-019 reports (AdminReportsResource) — the
 * ReconciliationStore pattern: hand-written SQL, one record per CSV row,
 * nothing written.
 *
 * <p>Report A (register of members, constitution clause 4 / CR-011 Stage 1)
 * pins two derivations:
 * <ul>
 * <li><b>Date became a member</b> = the earliest {@code membership.start_date}
 *     among the person's formal places ({@code is_statutory_member} rows) —
 *     NOT {@code membership_person.start_date}, which is the row-creation date
 *     (a rollover run's date for carried members). For CR-002-imported members
 *     this is the earliest imported period's start, not their true historical
 *     join date — the documented limitation.</li>
 * <li><b>Date ceased</b> (blank while current): a person is current while they
 *     hold a formal place on an ACTIVE or PENDING_PAYMENT membership whose
 *     {@code end_date} has not passed (an unpaid member inside the year is
 *     still a member until lapsed/ceased — clause 12). Otherwise, from their
 *     latest place: a CEASED membership contributes its {@code ceased_date};
 *     anything else (LAPSED, or simply never renewed) contributes the end of
 *     that last membership year, capped at today — the clause 12 non-payment
 *     mapping ("dropped off the roll when their last year ran out").</li>
 * </ul>
 * The CR-011 Stage 2 suppression flag does not exist yet; when it lands, the
 * register query is where it gets honoured (name-only row).
 */
class ReportStore {

    private final Jdbi jdbi;

    ReportStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    // ---- Report A: register of members --------------------------------------

    record RegisterRow(String familyName, String givenName, String address,
                       LocalDate becameMember, LocalDate ceased) {}

    List<RegisterRow> registerOfMembers() {
        return jdbi.withHandle(handle -> handle.createQuery(
                "SELECT p.family_name, p.given_name,"
                + " a.line_1, a.line_2, a.locality, a.state, a.postcode,"
                + " e.email,"
                + " pl.became_member, pl.current, last.status AS last_status,"
                + " last.ceased_date AS last_ceased, last.end_date AS last_end"
                + " FROM person p"
                // the formal places, aggregated: earliest membership start +
                // whether any place is still running
                + " JOIN LATERAL ("
                + "   SELECT MIN(m.start_date) AS became_member,"
                + "          BOOL_OR(m.status IN ('ACTIVE', 'PENDING_PAYMENT')"
                + "                  AND m.end_date >= current_date) AS current"
                + "   FROM membership_person mp"
                + "   JOIN membership m ON m.membership_id = mp.membership_id"
                + "   WHERE mp.person_id = p.person_id AND mp.is_statutory_member) pl"
                + "   ON pl.became_member IS NOT NULL"
                // the latest place, for the ceased derivation
                + " JOIN LATERAL ("
                + "   SELECT m.status, m.ceased_date, m.end_date"
                + "   FROM membership_person mp"
                + "   JOIN membership m ON m.membership_id = mp.membership_id"
                + "   WHERE mp.person_id = p.person_id AND mp.is_statutory_member"
                + "   ORDER BY m.end_date DESC, m.membership_id DESC LIMIT 1) last ON true"
                // best address: preferred postal of the person's current (else
                // latest) household — the mailing-labels address choice
                + " LEFT JOIN LATERAL ("
                + "   SELECT ha.line_1, ha.line_2, ha.locality, ha.state, ha.postcode"
                + "   FROM household_person hp"
                + "   JOIN household_address ha ON ha.household_id = hp.household_id"
                + "   WHERE hp.person_id = p.person_id AND ha.address_type = 'POSTAL'"
                + "   ORDER BY (hp.left_household_date IS NULL) DESC, hp.household_person_id DESC,"
                + "            ha.is_preferred DESC, ha.household_address_id DESC LIMIT 1) a ON true"
                + " LEFT JOIN LATERAL ("
                + "   SELECT ea.email FROM email_address ea WHERE ea.person_id = p.person_id"
                + "   ORDER BY ea.is_primary DESC, ea.email_id LIMIT 1) e ON true"
                + " ORDER BY p.family_name, p.given_name, p.person_id")
                .map((rs, ctx) -> {
                    String address = joinAddress(rs.getString("line_1"), rs.getString("line_2"),
                            rs.getString("locality"), rs.getString("state"), rs.getString("postcode"));
                    if (address == null) address = rs.getString("email");
                    LocalDate ceased = null;
                    if (!rs.getBoolean("current")) {
                        if ("CEASED".equals(rs.getString("last_status"))) {
                            ceased = localDate(rs.getDate("last_ceased"));
                        } else {
                            LocalDate end = localDate(rs.getDate("last_end"));
                            LocalDate today = LocalDate.now();
                            ceased = end != null && end.isBefore(today) ? end : today;
                        }
                    }
                    return new RegisterRow(rs.getString("family_name"), rs.getString("given_name"),
                            address, localDate(rs.getDate("became_member")), ceased);
                }).list());
    }

    // ---- Report B: people without a current membership ----------------------

    record NoMembershipRow(String familyName, String givenName, String household,
                           String relationship, String email, String phone, String lastPeriod) {}

    /** Current household people (not left, not deceased) holding no place at all in the period. */
    List<NoMembershipRow> noCurrentMembership(long periodId) {
        return jdbi.withHandle(handle -> handle.createQuery(
                "SELECT p.family_name, p.given_name, hp.relationship_type,"
                + " COALESCE(NULLIF(trim(h.household_name), ''),"
                + "          trim(pc.given_name || ' ' || pc.family_name)) AS household,"
                + " e.email, ph.number AS phone, lastp.name AS last_period"
                + " FROM household_person hp"
                + " JOIN person p ON p.person_id = hp.person_id"
                + " JOIN household h ON h.household_id = hp.household_id"
                + " JOIN person pc ON pc.person_id = h.primary_contact_person_id"
                + " LEFT JOIN LATERAL ("
                + "   SELECT ea.email FROM email_address ea WHERE ea.person_id = p.person_id"
                + "   ORDER BY ea.is_primary DESC, ea.email_id LIMIT 1) e ON true"
                + " LEFT JOIN LATERAL ("
                + "   SELECT pn.number FROM phone_number pn WHERE pn.person_id = p.person_id"
                + "   ORDER BY pn.is_primary DESC, pn.phone_number_id LIMIT 1) ph ON true"
                + " LEFT JOIN LATERAL ("
                + "   SELECT pd.name FROM membership_person mp"
                + "   JOIN membership m ON m.membership_id = mp.membership_id"
                + "   JOIN membership_period pd ON pd.membership_period_id = m.membership_period_id"
                + "   WHERE mp.person_id = p.person_id"
                + "   ORDER BY pd.start_date DESC LIMIT 1) lastp ON true"
                + " WHERE hp.left_household_date IS NULL AND p.deceased_date IS NULL"
                + " AND NOT EXISTS (SELECT 1 FROM membership_person mp"
                + "   JOIN membership m ON m.membership_id = mp.membership_id"
                + "   WHERE mp.person_id = p.person_id AND m.membership_period_id = :period)"
                + " ORDER BY p.family_name, p.given_name, p.person_id")
                .bind("period", periodId)
                .map((rs, ctx) -> new NoMembershipRow(rs.getString("family_name"),
                        rs.getString("given_name"), rs.getString("household"),
                        rs.getString("relationship_type"), rs.getString("email"),
                        rs.getString("phone"), rs.getString("last_period")))
                .list());
    }

    // ---- Report C: unrenewed households -------------------------------------

    record UnrenewedRow(String household, String primaryContact, String email, String phone,
                        String fromType, String toStatus) {}

    /** Households ACTIVE in the from-period whose to-period state is anything else (null = none). */
    List<UnrenewedRow> unrenewed(long fromPeriodId, long toPeriodId) {
        return jdbi.withHandle(handle -> handle.createQuery(
                "SELECT COALESCE(NULLIF(trim(h.household_name), ''),"
                + "          trim(pc.given_name || ' ' || pc.family_name)) AS household,"
                + " trim(pc.given_name || ' ' || pc.family_name) AS primary_contact,"
                + " e.email, ph.number AS phone, mt.name AS from_type, m2.status AS to_status"
                + " FROM membership m1"
                + " JOIN household h ON h.household_id = m1.household_id"
                + " JOIN person pc ON pc.person_id = h.primary_contact_person_id"
                + " JOIN membership_type mt ON mt.membership_type_id = m1.membership_type_id"
                + " LEFT JOIN membership m2 ON m2.household_id = m1.household_id"
                + "   AND m2.membership_period_id = :to"
                + " LEFT JOIN LATERAL ("
                + "   SELECT ea.email FROM email_address ea WHERE ea.person_id = pc.person_id"
                + "   ORDER BY ea.is_primary DESC, ea.email_id LIMIT 1) e ON true"
                + " LEFT JOIN LATERAL ("
                + "   SELECT pn.number FROM phone_number pn WHERE pn.person_id = pc.person_id"
                + "   ORDER BY pn.is_primary DESC, pn.phone_number_id LIMIT 1) ph ON true"
                + " WHERE m1.membership_period_id = :from AND m1.status = 'ACTIVE'"
                + " AND (m2.membership_id IS NULL OR m2.status <> 'ACTIVE')"
                + " ORDER BY household, h.household_id")
                .bind("from", fromPeriodId).bind("to", toPeriodId)
                .map((rs, ctx) -> new UnrenewedRow(rs.getString("household"),
                        rs.getString("primary_contact"), rs.getString("email"),
                        rs.getString("phone"), rs.getString("from_type"),
                        rs.getString("to_status")))
                .list());
    }

    // ---- Report D: donations ------------------------------------------------

    record DonationRow(long paymentId, LocalDate receivedDate, String payer, String method,
                       int donationCents, int paymentTotalCents, String externalTransactionId) {}

    /**
     * Payments in the received-date range carrying a DONATION allocation, the
     * donation part summed per payment. Reversals appear as negative rows —
     * the export sums to the ledger, not past it. Null bounds are open ends.
     */
    List<DonationRow> donations(LocalDate from, LocalDate to) {
        return jdbi.withHandle(handle -> handle.createQuery(
                "SELECT p.payment_id, p.received_date, p.payment_method, p.amount_cents,"
                + " p.external_transaction_id,"
                + " trim(pp.given_name || ' ' || pp.family_name) AS payer,"
                + " d.donation_cents"
                + " FROM payment p"
                + " JOIN LATERAL ("
                + "   SELECT SUM(pa.amount_cents) AS donation_cents FROM payment_allocation pa"
                + "   WHERE pa.payment_id = p.payment_id AND pa.allocation_type = 'DONATION') d"
                + "   ON d.donation_cents IS NOT NULL"
                + " LEFT JOIN person pp ON pp.person_id = p.payer_person_id"
                + " WHERE (CAST(:from AS date) IS NULL OR p.received_date >= :from)"
                + "   AND (CAST(:to AS date) IS NULL OR p.received_date <= :to)"
                + " ORDER BY p.received_date, p.payment_id")
                .bind("from", from).bind("to", to)
                .map((rs, ctx) -> new DonationRow(rs.getLong("payment_id"),
                        rs.getDate("received_date").toLocalDate(), rs.getString("payer"),
                        rs.getString("payment_method"), rs.getInt("donation_cents"),
                        rs.getInt("amount_cents"), rs.getString("external_transaction_id")))
                .list());
    }

    // ---- helpers ------------------------------------------------------------

    private static String joinAddress(String line1, String line2, String locality,
                                      String state, String postcode) {
        if (line1 == null || line1.isBlank()) return null;
        StringBuilder sb = new StringBuilder(line1.trim());
        for (String part : new String[]{line2, locality, state, postcode}) {
            if (part != null && !part.isBlank()) sb.append(", ").append(part.trim());
        }
        return sb.toString();
    }

    private static LocalDate localDate(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
