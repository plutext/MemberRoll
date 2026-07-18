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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The committee register (CR-013): a committee position is a term-bounded
 * appointment of a person to an office, AGM to AGM (constitution cl. 14-17).
 * The temporal shape copies {@link HouseholdStore}'s household_person — a
 * current term has a null {@code ended_date}, and history falls out for free
 * because each term is its own row carrying its own office.
 *
 * Write methods take the caller's {@link Handle} so a resource can run the
 * AGM roll (close every open term, insert the new slate) as one transaction —
 * the CR-010 discipline: every rejection is a thrown exception inside the
 * transaction lambda, never an early-return {@code Response}, so a bad line
 * rolls the whole roll back rather than leaving the committee half-replaced.
 * Corrections are edits ({@link #update})/deletes ({@link #delete}), not
 * reversals: this is administrative reference data, not a financial ledger.
 */
final class CommitteeStore {

    /** The offices, in constitutional precedence (cl. 14). */
    static final List<String> OFFICES =
            List.of("PRESIDENT", "VICE_PRESIDENT", "SECRETARY", "TREASURER", "ORDINARY");
    /** The singular offices (one holder each); ORDINARY seats are unbounded. */
    static final List<String> SINGULAR_OFFICES =
            List.of("PRESIDENT", "VICE_PRESIDENT", "SECRETARY", "TREASURER");

    private static final Map<String, String> OFFICE_LABELS = Map.of(
            "PRESIDENT", "president", "VICE_PRESIDENT", "vice-president",
            "SECRETARY", "secretary", "TREASURER", "treasurer", "ORDINARY", "ordinary committee member");

    // office precedence for ORDER BY (president first, ordinary last)
    private static final String OFFICE_RANK =
            "CASE ca.office WHEN 'PRESIDENT' THEN 0 WHEN 'VICE_PRESIDENT' THEN 1"
            + " WHEN 'SECRETARY' THEN 2 WHEN 'TREASURER' THEN 3 ELSE 4 END";

    private static final String COLS =
            "ca.committee_appointment_id, ca.person_id,"
            + " trim(pe.given_name || ' ' || pe.family_name) AS person_name,"
            + " ca.office, ca.started_date, ca.ended_date, ca.elected_date,"
            + " ca.minute_ref, ca.notes, ca.recorded_by";

    record Appointment(long id, long personId, String personName, String office,
                       LocalDate startedDate, LocalDate endedDate, LocalDate electedDate,
                       String minuteRef, String notes, String recordedBy) {}

    /** One line of a proposed AGM slate. */
    record SlateLine(long personId, String office, String notes) {}

    /** A committee member's routing address (CR-007 seam); email may be null. */
    record Contact(long personId, String personName, String office, String email) {}

    private final Jdbi jdbi;

    CommitteeStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    // ---- reads --------------------------------------------------------------

    /** The current committee (ended_date IS NULL), or full history newest term first. */
    List<Appointment> list(boolean includeEnded) {
        return jdbi.withHandle(handle -> list(handle, includeEnded));
    }

    static List<Appointment> list(Handle handle, boolean includeEnded) {
        String order = includeEnded
                ? " ORDER BY ca.started_date DESC, " + OFFICE_RANK + ", person_name, ca.committee_appointment_id"
                : " ORDER BY " + OFFICE_RANK + ", person_name, ca.committee_appointment_id";
        return handle.createQuery("SELECT " + COLS
                + " FROM committee_appointment ca JOIN person pe ON pe.person_id = ca.person_id"
                + (includeEnded ? "" : " WHERE ca.ended_date IS NULL") + order)
                .map((rs, ctx) -> map(rs)).list();
    }

    Optional<Appointment> find(Handle handle, long id) {
        return handle.createQuery("SELECT " + COLS
                + " FROM committee_appointment ca JOIN person pe ON pe.person_id = ca.person_id"
                + " WHERE ca.committee_appointment_id = :id")
                .bind("id", id).map((rs, ctx) -> map(rs)).findOne();
    }

    /** Current committee members with their primary email (CR-007 routing seam). */
    List<Contact> contacts() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT " + COLS + ","
                + " (SELECT email FROM email_address e WHERE e.person_id = ca.person_id"
                + "  ORDER BY e.is_primary DESC, e.email_id LIMIT 1) AS email"
                + " FROM committee_appointment ca JOIN person pe ON pe.person_id = ca.person_id"
                + " WHERE ca.ended_date IS NULL"
                + " ORDER BY " + OFFICE_RANK + ", person_name, ca.committee_appointment_id")
                .map((rs, ctx) -> new Contact(rs.getLong("person_id"), rs.getString("person_name"),
                        rs.getString("office"), rs.getString("email"))).list());
    }

    // ---- AGM roll (close-all-then-open-new) ---------------------------------

    /**
     * Record a newly-elected committee: end every currently-open appointment at
     * {@code agmDate} and insert the new slate (started = elected = agmDate), all
     * in the caller's transaction. Rejections (thrown, so the roll rolls back):
     * an unknown person, a singular office listed twice, the same person given
     * both president and vice-president, or a duplicate (person, office) line.
     * A missing office or a non-member appointee is a returned warning, not a
     * block — the register must be able to represent an interim gap.
     */
    List<String> agmRoll(Handle handle, LocalDate agmDate, String minuteRef, List<SlateLine> slate,
                         String recordedBy) {
        // --- validate before writing (all rejections are thrown) -------------
        Map<Long, List<String>> officesByPerson = new LinkedHashMap<>();
        Map<String, Integer> singularCounts = new LinkedHashMap<>();
        for (SlateLine line : slate) {
            if (!OFFICES.contains(line.office())) {
                throw new IllegalArgumentException("unknown office " + line.office());
            }
            List<String> held = officesByPerson.computeIfAbsent(line.personId(), k -> new ArrayList<>());
            if (held.contains(line.office())) {
                throw new IllegalArgumentException("person #" + line.personId()
                        + " is listed for " + OFFICE_LABELS.get(line.office()) + " twice");
            }
            held.add(line.office());
            if (!"ORDINARY".equals(line.office())) {
                singularCounts.merge(line.office(), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : singularCounts.entrySet()) {
            if (e.getValue() > 1) {
                throw new IllegalArgumentException("more than one " + OFFICE_LABELS.get(e.getKey())
                        + " in the slate — a singular office has one holder");
            }
        }
        for (Map.Entry<Long, List<String>> e : officesByPerson.entrySet()) {
            if (e.getValue().contains("PRESIDENT") && e.getValue().contains("VICE_PRESIDENT")) {
                throw new IllegalArgumentException("person #" + e.getKey()
                        + " cannot hold both president and vice-president (cl. 14(2))");
            }
        }
        for (long personId : officesByPerson.keySet()) {
            if (!personExists(handle, personId)) {
                throw new IllegalArgumentException("no such person #" + personId);
            }
        }
        // an AGM date before a currently-serving term's start would make
        // close-all set ended_date < started_date (the CHECK) — surface it as a
        // clean 400, not a raw SQL error
        LocalDate earliestOpenStart = handle.createQuery(
                "SELECT min(started_date) FROM committee_appointment WHERE ended_date IS NULL")
                .mapTo(LocalDate.class).findOne().orElse(null);
        if (earliestOpenStart != null && agmDate.isBefore(earliestOpenStart)) {
            throw new IllegalArgumentException("agmDate " + agmDate + " is before a currently-serving"
                    + " appointment that began " + earliestOpenStart + " — a later AGM date is required");
        }

        // --- write: close all open, then insert the new slate ----------------
        handle.createUpdate("UPDATE committee_appointment SET ended_date = :agm"
                + " WHERE ended_date IS NULL").bind("agm", agmDate).execute();
        for (SlateLine line : slate) {
            insert(handle, line.personId(), line.office(), agmDate, agmDate, minuteRef,
                    line.notes(), recordedBy);
        }

        // --- warnings (advisory, never blocking) -----------------------------
        List<String> warnings = new ArrayList<>();
        for (String office : SINGULAR_OFFICES) {
            if (!singularCounts.containsKey(office)) {
                warnings.add("no " + OFFICE_LABELS.get(office) + " in this slate");
            }
        }
        for (long personId : officesByPerson.keySet()) {
            if (!isCurrentStatutoryMember(handle, personId)) {
                warnings.add(personName(handle, personId) + " (#" + personId
                        + ") is not a current statutory member");
            }
        }
        return warnings;
    }

    // ---- single-appointment writes (casual vacancy, correction) -------------

    /**
     * Open one appointment (fill a casual vacancy, or record an omission). 400
     * for an unknown person or bad office; 409 if that person already holds that
     * office, or the singular office is already filled. Returns any advisory
     * warnings (currently the not-a-member soft guard).
     */
    Appointment open(Handle handle, long personId, String office, LocalDate startedDate,
                     LocalDate electedDate, String minuteRef, String notes, String recordedBy,
                     List<String> warningsOut) {
        if (!OFFICES.contains(office)) throw new IllegalArgumentException("unknown office " + office);
        if (!personExists(handle, personId)) throw new IllegalArgumentException("no such person #" + personId);
        if (!"ORDINARY".equals(office) && openHolder(handle, office, 0) != null) {
            throw new ConflictException("the " + OFFICE_LABELS.get(office) + " position is already filled");
        }
        if (holdsOpen(handle, personId, office, 0)) {
            throw new ConflictException("that person already holds the " + OFFICE_LABELS.get(office) + " position");
        }
        long id = insert(handle, personId, office, startedDate,
                electedDate != null ? electedDate : startedDate, minuteRef, notes, recordedBy);
        if (!isCurrentStatutoryMember(handle, personId)) {
            warningsOut.add(personName(handle, personId) + " (#" + personId
                    + ") is not a current statutory member");
        }
        return find(handle, id).orElseThrow();
    }

    /**
     * Correct an appointment in place — office, dates, minute ref, notes (person
     * is fixed). Setting {@code endedDate} closes the term (a resignation). 409
     * if the change would collide with another open holder of a singular office.
     * Empty if no such appointment.
     */
    Optional<Appointment> update(Handle handle, long id, String office, LocalDate startedDate,
                                 LocalDate endedDate, LocalDate electedDate, String minuteRef, String notes) {
        Optional<Appointment> existing = find(handle, id);
        if (existing.isEmpty()) return Optional.empty();
        if (!OFFICES.contains(office)) throw new IllegalArgumentException("unknown office " + office);
        long personId = existing.get().personId();
        boolean staysOpen = endedDate == null;
        if (staysOpen) {
            if (!"ORDINARY".equals(office) && openHolder(handle, office, id) != null) {
                throw new ConflictException("the " + OFFICE_LABELS.get(office) + " position is already filled");
            }
            if (holdsOpen(handle, personId, office, id)) {
                throw new ConflictException("that person already holds the " + OFFICE_LABELS.get(office) + " position");
            }
        }
        handle.createUpdate("UPDATE committee_appointment SET office = :office,"
                + " started_date = :started, ended_date = :ended, elected_date = :elected,"
                + " minute_ref = :minute, notes = :notes WHERE committee_appointment_id = :id")
                .bind("office", office).bind("started", startedDate).bind("ended", endedDate)
                .bind("elected", electedDate).bind("minute", minuteRef).bind("notes", notes)
                .bind("id", id).execute();
        return find(handle, id);
    }

    /** Remove a mistaken row entirely; false if no such appointment. */
    boolean delete(Handle handle, long id) {
        return handle.createUpdate("DELETE FROM committee_appointment WHERE committee_appointment_id = :id")
                .bind("id", id).execute() > 0;
    }

    // ---- helpers ------------------------------------------------------------

    private static long insert(Handle handle, long personId, String office, LocalDate startedDate,
                               LocalDate electedDate, String minuteRef, String notes, String recordedBy) {
        return handle.createUpdate("INSERT INTO committee_appointment"
                + " (person_id, office, started_date, elected_date, minute_ref, notes, recorded_by)"
                + " VALUES (:pid, :office, :started, :elected, :minute, :notes, :by)")
                .bind("pid", personId).bind("office", office).bind("started", startedDate)
                .bind("elected", electedDate).bind("minute", minuteRef).bind("notes", notes)
                .bind("by", recordedBy)
                .executeAndReturnGeneratedKeys("committee_appointment_id").mapTo(Long.class).one();
    }

    private static boolean personExists(Handle handle, long personId) {
        return handle.createQuery("SELECT count(*) FROM person WHERE person_id = :id")
                .bind("id", personId).mapTo(Integer.class).one() > 0;
    }

    private static String personName(Handle handle, long personId) {
        return handle.createQuery("SELECT trim(given_name || ' ' || family_name) FROM person WHERE person_id = :id")
                .bind("id", personId).mapTo(String.class).findOne().orElse("person #" + personId);
    }

    /** The current holder's appointment id for a singular office, or null (excluding {@code exceptId}). */
    private static Long openHolder(Handle handle, String office, long exceptId) {
        return handle.createQuery("SELECT committee_appointment_id FROM committee_appointment"
                + " WHERE office = :office AND ended_date IS NULL AND committee_appointment_id <> :except")
                .bind("office", office).bind("except", exceptId).mapTo(Long.class).findFirst().orElse(null);
    }

    private static boolean holdsOpen(Handle handle, long personId, String office, long exceptId) {
        return handle.createQuery("SELECT count(*) FROM committee_appointment"
                + " WHERE person_id = :pid AND office = :office AND ended_date IS NULL"
                + " AND committee_appointment_id <> :except")
                .bind("pid", personId).bind("office", office).bind("except", exceptId)
                .mapTo(Integer.class).one() > 0;
    }

    /** Soft guard (warned, not blocked): a MEMBER-relationship person on an ACTIVE membership. */
    private static boolean isCurrentStatutoryMember(Handle handle, long personId) {
        return handle.createQuery("SELECT count(*) FROM membership_person mp"
                + " JOIN membership m ON m.membership_id = mp.membership_id"
                + " WHERE mp.person_id = :pid AND mp.is_statutory_member AND m.status = 'ACTIVE'")
                .bind("pid", personId).mapTo(Integer.class).one() > 0;
    }

    private static Appointment map(ResultSet rs) {
        try {
            return new Appointment(rs.getLong("committee_appointment_id"), rs.getLong("person_id"),
                    rs.getString("person_name"), rs.getString("office"),
                    localDate(rs, "started_date"), localDate(rs, "ended_date"),
                    localDate(rs, "elected_date"), rs.getString("minute_ref"),
                    rs.getString("notes"), rs.getString("recorded_by"));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }
}
