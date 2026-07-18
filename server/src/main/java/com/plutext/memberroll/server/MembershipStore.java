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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Memberships and their covered people (CR-003) — the annual entitlement a
 * household holds for a period. Paid-ness is never stored: it derives from
 * MEMBERSHIP payment allocations (schema rule 6), and {@link #recompute} is
 * the single place status follows money (paid ≥ due activates; a reversal
 * that drops paid below due demotes). Activation is therefore never hand-set;
 * the one exception is zero-due (life/honorary) memberships, ACTIVE at
 * creation because 0 ≥ 0 is the same rule.
 *
 * Write methods take the caller's {@link Handle} so a resource can compose a
 * payment insert and the resulting recompute in one transaction; read methods
 * open their own. The low-level {@link #insertMembership}/{@link
 * #insertMembershipPerson} primitives are shared with {@link ImportService}.
 */
final class MembershipStore {

    /**
     * Rule 6 as SQL: the paid-ness of the membership aliased {@code m}. The
     * ONE definition — every query that derives paid (here, and the CR-004
     * pay-view/lost-link queries in {@link RenewalTokenStore}) concatenates
     * this, so an amendment to what counts as paid cannot drift between the
     * admin screens and the amount a member is charged.
     */
    static final String PAID_SQL = "COALESCE((SELECT SUM(pa.amount_cents) FROM payment_allocation pa"
            + " WHERE pa.membership_id = m.membership_id AND pa.allocation_type = 'MEMBERSHIP'), 0)";

    record Person(long personId, String givenName, String familyName, String role,
                  boolean statutory, boolean voting, boolean committee) {}
    record Detail(long id, long periodId, String periodName, long typeId, String typeName,
                  long householdId, String householdName, String status,
                  LocalDate applicationDate, LocalDate approvedDate, LocalDate startDate, LocalDate endDate,
                  int amountDueCents, int amountPaidCents, LocalDate ceasedDate, String cessationReason,
                  List<Person> people) {}

    /** A row of the treasurer's financial-status view. */
    record StatusRow(long membershipId, long householdId, String householdName,
                     String primaryContactName, List<String> memberNames,
                     String typeName, String status, int amountDueCents, int amountPaidCents) {}
    record Summary(Map<String, Integer> countsByStatus, long totalDueCents, long totalCollectedCents) {}
    record StatusPage(List<StatusRow> rows, int total, Summary summary) {}

    /** Result of creating a membership: the caller renders id + any warning. */
    record CreateOutcome(long membershipId, String status, int amountDueCents, boolean lateJoiningWarning) {}

    record Skip(long householdId, String reason) {}
    /** {@code created} is null on preview, the count applied otherwise. */
    record RolloverReport(long targetPeriodId, String targetPeriodName,
                          Long fromPeriodId, String fromPeriodName,
                          int toCreate, List<Skip> skipped, List<String> errors, Integer created) {}

    private final Jdbi jdbi;

    MembershipStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    // ---- reads --------------------------------------------------------------

    Optional<Detail> get(long id) {
        return jdbi.withHandle(handle -> get(handle, id));
    }

    static Optional<Detail> get(Handle handle, long id) {
        return handle.createQuery(
                "SELECT m.membership_id, m.membership_period_id, per.name AS period_name,"
                + " m.membership_type_id, mt.name AS type_name, m.household_id, h.household_name,"
                + " m.status, m.application_date, m.approved_date, m.start_date, m.end_date,"
                + " m.amount_due_cents, m.ceased_date, m.cessation_reason,"
                + " " + PAID_SQL + " AS paid"
                + " FROM membership m"
                + " JOIN membership_period per ON per.membership_period_id = m.membership_period_id"
                + " JOIN membership_type mt ON mt.membership_type_id = m.membership_type_id"
                + " JOIN household h ON h.household_id = m.household_id"
                + " WHERE m.membership_id = :id")
                .bind("id", id)
                .map((rs, ctx) -> mapDetail(rs))
                .findOne()
                .map(d -> new Detail(d.id(), d.periodId(), d.periodName(), d.typeId(), d.typeName(),
                        d.householdId(), d.householdName(), d.status(), d.applicationDate(),
                        d.approvedDate(), d.startDate(), d.endDate(), d.amountDueCents(),
                        d.amountPaidCents(), d.ceasedDate(), d.cessationReason(), people(handle, id)));
    }

    private static List<Person> people(Handle handle, long membershipId) {
        return handle.createQuery(
                "SELECT mp.person_id, pe.given_name, pe.family_name, mp.membership_role,"
                + " mp.is_statutory_member, mp.has_voting_rights, mp.eligible_for_committee"
                + " FROM membership_person mp JOIN person pe ON pe.person_id = mp.person_id"
                + " WHERE mp.membership_id = :id ORDER BY mp.membership_person_id")
                .bind("id", membershipId)
                .map((rs, ctx) -> new Person(rs.getLong("person_id"), rs.getString("given_name"),
                        rs.getString("family_name"), rs.getString("membership_role"),
                        rs.getBoolean("is_statutory_member"), rs.getBoolean("has_voting_rights"),
                        rs.getBoolean("eligible_for_committee")))
                .list();
    }

    /** The financial-status view: page of rows plus an unfiltered summary. */
    StatusPage statusPage(long periodId, String status, String type, String q, int limit, int offset) {
        boolean filtered = q != null && !q.isBlank();
        StringBuilder where = new StringBuilder(" WHERE m.membership_period_id = :period");
        if (status != null) where.append(" AND m.status = :status");
        if (type != null) where.append(" AND mt.name = :type");
        if (filtered) {
            where.append(" AND (h.household_name ILIKE :pat"
                    + " OR EXISTS (SELECT 1 FROM membership_person mp JOIN person pe ON pe.person_id = mp.person_id"
                    + "            WHERE mp.membership_id = m.membership_id"
                    + "            AND (pe.given_name ILIKE :pat OR pe.family_name ILIKE :pat)))");
        }
        return jdbi.withHandle(handle -> {
            var count = handle.createQuery("SELECT count(*) FROM membership m"
                    + " JOIN household h ON h.household_id = m.household_id"
                    + " JOIN membership_type mt ON mt.membership_type_id = m.membership_type_id" + where);
            var page = handle.createQuery(
                    "SELECT m.membership_id, m.household_id, h.household_name,"
                    + " trim(pc.given_name || ' ' || pc.family_name) AS contact_name,"
                    + " mt.name AS type_name, m.status, m.amount_due_cents,"
                    + " " + PAID_SQL + " AS paid"
                    + " FROM membership m"
                    + " JOIN household h ON h.household_id = m.household_id"
                    + " JOIN person pc ON pc.person_id = h.primary_contact_person_id"
                    + " JOIN membership_type mt ON mt.membership_type_id = m.membership_type_id" + where
                    + " ORDER BY h.household_name NULLS LAST, m.membership_id LIMIT :limit OFFSET :offset")
                    .bind("limit", limit).bind("offset", offset);
            count.bind("period", periodId);
            page.bind("period", periodId);
            if (status != null) { count.bind("status", status); page.bind("status", status); }
            if (type != null) { count.bind("type", type); page.bind("type", type); }
            if (filtered) {
                String pattern = "%" + PersonStore.escapeLike(q.trim()) + "%";
                count.bind("pat", pattern);
                page.bind("pat", pattern);
            }
            int total = count.mapTo(Integer.class).one();
            List<StatusRow> rows = page.map((rs, ctx) -> new StatusRow(
                    rs.getLong("membership_id"), rs.getLong("household_id"),
                    rs.getString("household_name"), rs.getString("contact_name"),
                    new ArrayList<>(), rs.getString("type_name"), rs.getString("status"),
                    rs.getInt("amount_due_cents"), rs.getInt("paid"))).list();
            return new StatusPage(attachMemberNames(handle, rows), total, summary(handle, periodId));
        });
    }

    /** Every membership of a period as status rows (unpaginated) — for the CSV export. */
    List<StatusRow> allStatusRows(long periodId) {
        return statusPage(periodId, null, null, null, Integer.MAX_VALUE, 0).rows();
    }

    // ---- CSV export rows ----------------------------------------------------

    /** One row per voting member of an ACTIVE membership (the AGM register). */
    record AgmRow(String familyName, String givenName, String household, String type) {}

    List<AgmRow> agmRegister(long periodId) {
        return jdbi.withHandle(handle -> handle.createQuery(
                "SELECT pe.family_name, pe.given_name,"
                + " COALESCE(NULLIF(trim(h.household_name), ''),"
                + "          trim(pc.given_name || ' ' || pc.family_name)) AS household,"
                + " mt.name AS type"
                + " FROM membership m"
                + " JOIN membership_person mp ON mp.membership_id = m.membership_id"
                + " JOIN person pe ON pe.person_id = mp.person_id"
                + " JOIN household h ON h.household_id = m.household_id"
                + " JOIN person pc ON pc.person_id = h.primary_contact_person_id"
                + " JOIN membership_type mt ON mt.membership_type_id = m.membership_type_id"
                + " WHERE m.membership_period_id = :period AND m.status = 'ACTIVE'"
                + "   AND mp.has_voting_rights = true"
                + " ORDER BY pe.family_name, pe.given_name, pe.person_id")
                .bind("period", periodId)
                .map((rs, ctx) -> new AgmRow(rs.getString("family_name"), rs.getString("given_name"),
                        rs.getString("household"), rs.getString("type"))).list());
    }

    /** One row per household with an ACTIVE membership; addressless ones carry blank columns. */
    record MailingLabel(String name, String line1, String line2, String locality,
                        String state, String postcode) {}

    List<MailingLabel> mailingLabels(long periodId) {
        return jdbi.withHandle(handle -> handle.createQuery(
                "SELECT COALESCE(NULLIF(trim(h.household_name), ''),"
                + "               trim(pc.given_name || ' ' || pc.family_name)) AS name,"
                + " a.line_1, a.line_2, a.locality, a.state, a.postcode"
                + " FROM membership m"
                + " JOIN household h ON h.household_id = m.household_id"
                + " JOIN person pc ON pc.person_id = h.primary_contact_person_id"
                + " LEFT JOIN LATERAL ("
                + "   SELECT line_1, line_2, locality, state, postcode FROM household_address ha"
                + "   WHERE ha.household_id = h.household_id AND ha.address_type = 'POSTAL'"
                + "   ORDER BY ha.is_preferred DESC, ha.household_address_id DESC LIMIT 1) a ON true"
                + " WHERE m.membership_period_id = :period AND m.status = 'ACTIVE'"
                + " ORDER BY name, h.household_id")
                .bind("period", periodId)
                .map((rs, ctx) -> new MailingLabel(rs.getString("name"), rs.getString("line_1"),
                        rs.getString("line_2"), rs.getString("locality"), rs.getString("state"),
                        rs.getString("postcode"))).list());
    }

    private static List<StatusRow> attachMemberNames(Handle handle, List<StatusRow> rows) {
        if (rows.isEmpty()) return rows;
        List<Long> ids = rows.stream().map(StatusRow::membershipId).toList();
        Map<Long, List<String>> names = new HashMap<>();
        handle.createQuery(
                "SELECT mp.membership_id, trim(pe.given_name || ' ' || pe.family_name) AS name"
                + " FROM membership_person mp JOIN person pe ON pe.person_id = mp.person_id"
                + " WHERE mp.membership_id IN (<ids>) ORDER BY mp.membership_person_id")
                .bindList("ids", ids)
                .map((rs, ctx) -> Map.entry(rs.getLong("membership_id"), rs.getString("name")))
                .forEach(e -> names.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return rows.stream().map(r -> new StatusRow(r.membershipId(), r.householdId(),
                r.householdName(), r.primaryContactName(),
                names.getOrDefault(r.membershipId(), List.of()), r.typeName(), r.status(),
                r.amountDueCents(), r.amountPaidCents())).toList();
    }

    private static Summary summary(Handle handle, long periodId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        long[] totalDue = {0};
        handle.createQuery("SELECT status, count(*) AS n, COALESCE(SUM(amount_due_cents), 0) AS due"
                + " FROM membership WHERE membership_period_id = :period GROUP BY status ORDER BY status")
                .bind("period", periodId)
                .map((rs, ctx) -> new Object[]{rs.getString("status"), rs.getInt("n"), rs.getLong("due")})
                .forEach(r -> { counts.put((String) r[0], (Integer) r[1]); totalDue[0] += (Long) r[2]; });
        long collected = handle.createQuery(
                "SELECT COALESCE(SUM(pa.amount_cents), 0) FROM payment_allocation pa"
                + " JOIN membership m ON m.membership_id = pa.membership_id"
                + " WHERE m.membership_period_id = :period AND pa.allocation_type = 'MEMBERSHIP'")
                .bind("period", periodId).mapTo(Long.class).one();
        return new Summary(counts, totalDue[0], collected);
    }

    // ---- creation primitives (shared with ImportService) --------------------

    long insertMembership(Handle handle, long periodId, long typeId, long householdId, String status,
                          LocalDate applicationDate, LocalDate approvedDate,
                          LocalDate start, LocalDate end, int amountDueCents) {
        return handle.createUpdate(
                "INSERT INTO membership (membership_period_id, membership_type_id, household_id,"
                + " status, application_date, approved_date, start_date, end_date, amount_due_cents)"
                + " VALUES (:period, :type, :hh, :status, :app, :approved, :start, :end, :due)")
                .bind("period", periodId).bind("type", typeId).bind("hh", householdId)
                .bind("status", status).bind("app", applicationDate).bind("approved", approvedDate)
                .bind("start", start).bind("end", end).bind("due", amountDueCents)
                .executeAndReturnGeneratedKeys("membership_id").mapTo(Long.class).one();
    }

    void insertMembershipPerson(Handle handle, long membershipId, long personId, String relationship) {
        // both adults in a household vote (the society's decision): MEMBER and
        // PARTNER carry statutory/voting/committee rights, dependants do not
        boolean adult = "MEMBER".equals(relationship) || "PARTNER".equals(relationship);
        handle.createUpdate(
                "INSERT INTO membership_person (membership_id, person_id, membership_role,"
                + " is_statutory_member, has_voting_rights, eligible_for_committee, start_date)"
                + " VALUES (:mid, :pid, :role, :adult, :adult, :adult, current_date)")
                .bind("mid", membershipId).bind("pid", personId).bind("role", relationship)
                .bind("adult", adult).execute();
    }

    /** Copy the household's CURRENT composition (present, not deceased) as membership_person rows. */
    void copyCurrentComposition(Handle handle, long membershipId, long householdId) {
        record Comp(long personId, String relationship) {}
        List<Comp> members = handle.createQuery(
                "SELECT hp.person_id, hp.relationship_type FROM household_person hp"
                + " JOIN person p ON p.person_id = hp.person_id"
                + " WHERE hp.household_id = :hh AND hp.left_household_date IS NULL"
                + " AND p.deceased_date IS NULL ORDER BY hp.household_person_id")
                .bind("hh", householdId)
                .map((rs, ctx) -> new Comp(rs.getLong("person_id"), rs.getString("relationship_type")))
                .list();
        for (Comp m : members) insertMembershipPerson(handle, membershipId, m.personId(), m.relationship());
    }

    // ---- creation, transitions, recompute (the resource's write API) --------

    /**
     * Create a membership for a household in a period, copying the current
     * composition. Zero-due (life/honorary) types are ACTIVE immediately;
     * otherwise PENDING_PAYMENT awaiting payment. 409 if the household already
     * has a membership that period. 400 (via IllegalArgumentException) for an
     * unknown household/period or a type with no price in the period.
     */
    CreateOutcome createForHousehold(Handle handle, long householdId, long periodId,
                                     long typeId, LocalDate startOverride) {
        if (!householdExists(handle, householdId)) throw new IllegalArgumentException("no such household");
        PeriodBounds bounds = periodBounds(handle, periodId);
        if (bounds == null) throw new IllegalArgumentException("no such membership period");
        Optional<Integer> price = PeriodStore.priceCents(handle, typeId, periodId);
        if (price.isEmpty()) throw new IllegalArgumentException("no price for that membership type in the period");
        if (hasMembership(handle, householdId, periodId)) {
            throw new ConflictException("household already has a membership in this period");
        }
        LocalDate today = today(handle);
        int due = price.get();
        LocalDate start = startOverride != null ? startOverride
                : (today.isAfter(bounds.start()) && today.isBefore(bounds.end()) ? today : bounds.start());
        if (!start.isBefore(bounds.end())) start = bounds.start(); // end_date > start_date CHECK
        boolean zeroDue = due == 0;
        String statusValue = zeroDue ? "ACTIVE" : "PENDING_PAYMENT";
        LocalDate approved = zeroDue ? today : null;
        long id = insertMembership(handle, periodId, typeId, householdId, statusValue,
                today, approved, start, bounds.end(), due);
        copyCurrentComposition(handle, id, householdId);
        boolean late = bounds.cutoff() != null && today.isAfter(bounds.cutoff());
        return new CreateOutcome(id, statusValue, due, late);
    }

    boolean exists(Handle handle, long id) {
        return handle.createQuery("SELECT count(*) FROM membership WHERE membership_id = :id")
                .bind("id", id).mapTo(Integer.class).one() > 0;
    }

    /**
     * Re-snapshot amount_due to a new type's price. Refused (400) once any
     * allocation exists — repricing under money is how registers drift. A
     * recompute follows in case the new type is zero-due.
     */
    void changeType(Handle handle, long id, long typeId) {
        int allocations = handle.createQuery("SELECT count(*) FROM payment_allocation WHERE membership_id = :id")
                .bind("id", id).mapTo(Integer.class).one();
        if (allocations > 0) {
            throw new IllegalArgumentException("cannot change membership type once a payment has been allocated");
        }
        long periodId = handle.createQuery("SELECT membership_period_id FROM membership WHERE membership_id = :id")
                .bind("id", id).mapTo(Long.class).one();
        Optional<Integer> price = PeriodStore.priceCents(handle, typeId, periodId);
        if (price.isEmpty()) {
            throw new IllegalArgumentException("no price for that membership type in the membership's period");
        }
        handle.createUpdate("UPDATE membership SET membership_type_id = :type, amount_due_cents = :due"
                + " WHERE membership_id = :id")
                .bind("type", typeId).bind("due", price.get()).bind("id", id).execute();
        recompute(handle, id, today(handle));
    }

    void cease(Handle handle, long id, LocalDate ceasedDate, String reason) {
        handle.createUpdate("UPDATE membership SET status = 'CEASED', ceased_date = :d, cessation_reason = :r"
                + " WHERE membership_id = :id")
                .bind("d", ceasedDate).bind("r", reason).bind("id", id).execute();
    }

    /** PENDING_PAYMENT → LAPSED; false if the membership was not pending. */
    boolean lapse(Handle handle, long id) {
        return handle.createUpdate("UPDATE membership SET status = 'LAPSED'"
                + " WHERE membership_id = :id AND status = 'PENDING_PAYMENT'").bind("id", id).execute() > 0;
    }

    /** LAPSED → PENDING_PAYMENT (undo lapse); false if not lapsed. */
    boolean unlapse(Handle handle, long id) {
        return handle.createUpdate("UPDATE membership SET status = 'PENDING_PAYMENT'"
                + " WHERE membership_id = :id AND status = 'LAPSED'").bind("id", id).execute() > 0;
    }

    /** Bulk: every PENDING_PAYMENT membership of the period → LAPSED; returns the count. */
    int lapseUnpaid(Handle handle, long periodId) {
        return handle.createUpdate("UPDATE membership SET status = 'LAPSED'"
                + " WHERE membership_period_id = :p AND status = 'PENDING_PAYMENT'")
                .bind("p", periodId).execute();
    }

    /**
     * Re-derive status from allocations after a payment write (rule 6). Called
     * once per membership a payment touches. CEASED is never disturbed.
     */
    void recompute(Handle handle, long membershipId, LocalDate receivedDate) {
        record State(String status, int due, int paid) {}
        State s = handle.createQuery(
                "SELECT m.status, m.amount_due_cents, " + PAID_SQL + " AS paid"
                + " FROM membership m WHERE m.membership_id = :id")
                .bind("id", membershipId)
                .map((rs, ctx) -> new State(rs.getString("status"), rs.getInt("amount_due_cents"), rs.getInt("paid")))
                .one();
        if ("CEASED".equals(s.status())) return;
        if (s.paid() >= s.due() && ("PENDING_PAYMENT".equals(s.status()) || "LAPSED".equals(s.status()))) {
            // a late payment reactivates a lapsed membership; approved_date is
            // the first activation and stays put on subsequent recomputes
            handle.createUpdate("UPDATE membership SET status = 'ACTIVE',"
                    + " approved_date = COALESCE(approved_date, :d) WHERE membership_id = :id")
                    .bind("d", receivedDate).bind("id", membershipId).execute();
        } else if (s.paid() < s.due() && "ACTIVE".equals(s.status())) {
            handle.createUpdate("UPDATE membership SET status = 'PENDING_PAYMENT' WHERE membership_id = :id")
                    .bind("id", membershipId).execute();
        }
    }

    // ---- rollover -----------------------------------------------------------

    RolloverReport rolloverPreview(long targetId, Long fromId) {
        return jdbi.withHandle(handle -> rollover(handle, targetId, fromId, false));
    }

    RolloverReport rolloverApply(Handle handle, long targetId, Long fromId) {
        return rollover(handle, targetId, fromId, true);
    }

    private RolloverReport rollover(Handle handle, long targetId, Long fromParam, boolean apply) {
        PeriodBounds target = periodBounds(handle, targetId);
        String targetName = periodName(handle, targetId);
        List<Skip> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        Long fromId;
        String fromName;
        if (fromParam != null) {
            fromName = periodName(handle, fromParam);
            if (fromName == null) {
                errors.add("no membership period #" + fromParam);
                return new RolloverReport(targetId, targetName, null, null, 0, skipped, errors, apply ? 0 : null);
            }
            fromId = fromParam;
        } else {
            record Src(long id, String name) {}
            Src src = handle.createQuery("SELECT membership_period_id, name FROM membership_period"
                    + " WHERE start_date < :ts ORDER BY start_date DESC LIMIT 1")
                    .bind("ts", target.start())
                    .map((rs, ctx) -> new Src(rs.getLong("membership_period_id"), rs.getString("name")))
                    .findOne().orElse(null);
            if (src == null) {
                errors.add("no earlier period to roll over from; pass ?from=<periodId>");
                return new RolloverReport(targetId, targetName, null, null, 0, skipped, errors, apply ? 0 : null);
            }
            fromId = src.id();
            fromName = src.name();
        }

        record Source(long householdId, long typeId, String typeName) {}
        List<Source> sources = handle.createQuery(
                "SELECT m.household_id, m.membership_type_id, mt.name AS type_name"
                + " FROM membership m JOIN membership_type mt ON mt.membership_type_id = m.membership_type_id"
                + " WHERE m.membership_period_id = :from AND m.status = 'ACTIVE' ORDER BY m.household_id")
                .bind("from", fromId)
                .map((rs, ctx) -> new Source(rs.getLong("household_id"), rs.getLong("membership_type_id"),
                        rs.getString("type_name"))).list();

        int toCreate = 0;
        LocalDate today = today(handle);
        for (Source src : sources) {
            if (hasMembership(handle, src.householdId(), targetId)) {
                skipped.add(new Skip(src.householdId(), "already has a membership in " + targetName));
                continue;
            }
            if (currentCompositionCount(handle, src.householdId()) == 0) {
                skipped.add(new Skip(src.householdId(), "no current household members"));
                continue;
            }
            Optional<Integer> price = PeriodStore.priceCents(handle, src.typeId(), targetId);
            if (price.isEmpty()) {
                errors.add("no price for type " + src.typeName() + " in " + targetName);
                continue;
            }
            toCreate++;
            if (apply) {
                int due = price.get();
                boolean zeroDue = due == 0;
                String statusValue = zeroDue ? "ACTIVE" : "PENDING_PAYMENT";
                LocalDate approved = zeroDue ? today : null;
                long id = insertMembership(handle, targetId, src.typeId(), src.householdId(),
                        statusValue, today, approved, target.start(), target.end(), due);
                copyCurrentComposition(handle, id, src.householdId());
            }
        }
        return new RolloverReport(targetId, targetName, fromId, fromName, toCreate, skipped, errors,
                apply ? toCreate : null);
    }

    // ---- small helpers ------------------------------------------------------

    private record PeriodBounds(LocalDate start, LocalDate end, LocalDate cutoff) {}

    private static PeriodBounds periodBounds(Handle handle, long periodId) {
        return handle.createQuery("SELECT start_date, end_date, late_joining_cutoff"
                + " FROM membership_period WHERE membership_period_id = :id")
                .bind("id", periodId)
                .map((rs, ctx) -> new PeriodBounds(rs.getDate("start_date").toLocalDate(),
                        rs.getDate("end_date").toLocalDate(),
                        rs.getDate("late_joining_cutoff") == null ? null
                                : rs.getDate("late_joining_cutoff").toLocalDate()))
                .findOne().orElse(null);
    }

    private static String periodName(Handle handle, long periodId) {
        return handle.createQuery("SELECT name FROM membership_period WHERE membership_period_id = :id")
                .bind("id", periodId).mapTo(String.class).findOne().orElse(null);
    }

    private static boolean householdExists(Handle handle, long householdId) {
        return handle.createQuery("SELECT count(*) FROM household WHERE household_id = :id")
                .bind("id", householdId).mapTo(Integer.class).one() > 0;
    }

    private static boolean hasMembership(Handle handle, long householdId, long periodId) {
        return handle.createQuery("SELECT count(*) FROM membership"
                + " WHERE household_id = :hh AND membership_period_id = :p")
                .bind("hh", householdId).bind("p", periodId).mapTo(Integer.class).one() > 0;
    }

    private static int currentCompositionCount(Handle handle, long householdId) {
        return handle.createQuery("SELECT count(*) FROM household_person hp"
                + " JOIN person p ON p.person_id = hp.person_id"
                + " WHERE hp.household_id = :hh AND hp.left_household_date IS NULL AND p.deceased_date IS NULL")
                .bind("hh", householdId).mapTo(Integer.class).one();
    }

    private static LocalDate today(Handle handle) {
        return handle.createQuery("SELECT current_date").map((rs, ctx) -> rs.getDate(1).toLocalDate()).one();
    }

    private static Detail mapDetail(ResultSet rs) {
        try {
            return new Detail(rs.getLong("membership_id"), rs.getLong("membership_period_id"),
                    rs.getString("period_name"), rs.getLong("membership_type_id"), rs.getString("type_name"),
                    rs.getLong("household_id"), rs.getString("household_name"), rs.getString("status"),
                    localDate(rs, "application_date"), localDate(rs, "approved_date"),
                    localDate(rs, "start_date"), localDate(rs, "end_date"),
                    rs.getInt("amount_due_cents"), rs.getInt("paid"),
                    localDate(rs, "ceased_date"), rs.getString("cessation_reason"), List.of());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }
}
