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

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Member-list CSV import (CR-002). Two phases share one validation pass:
 * {@link #preview} reads and reports but writes nothing; {@link #apply}
 * re-validates inside a transaction and, only if there are zero errors,
 * writes everything atomically. The canonical column set is fixed (the
 * treasurer massages their spreadsheet into it — see docs/import-template.csv);
 * unknown columns are an error so a header typo can't silently drop data.
 *
 * A row is a person; rows sharing a {@code household} label group into one
 * household (blank label becomes a one-person household of its own). Dedup is
 * by email or given+family name against the existing register: matched rows
 * are skipped, so re-running the same file after a partial cleanup converges
 * instead of duplicating. Memberships and payments are in scope because the
 * spreadsheet this replaces is fundamentally a record of who is financial.
 */
final class ImportService {

    static final long MAX_ROWS = 2000;

    // the one canonical column set; order is free, unknown columns are errors
    private static final Set<String> KNOWN_COLUMNS = Set.of(
            "household", "title", "givenName", "familyName", "preferredName",
            "relationship", "email", "phone", "phoneType",
            "line1", "line2", "locality", "state", "postcode",
            "membershipType", "paid", "notes");
    private static final Set<String> RELATIONSHIP_TYPES =
            Set.of("MEMBER", "PARTNER", "DEPENDANT", "OTHER");
    private static final Set<String> PHONE_TYPES = Set.of("MOBILE", "HOME", "WORK");

    // ---- report model (the resource renders these to JSON) ------------------

    record Issue(long line, String message) {}
    record Skip(long line, String reason) {}
    record Counts(int people, int households, int memberships, int payments) {}
    /** {@code created} is null on preview. */
    record Report(int rows, List<Issue> errors, List<Issue> warnings,
                  List<Skip> skipped, Counts toCreate, Counts created) {}

    private final Jdbi jdbi;
    // membership/payment writes go through the CR-003 stores (the flagged
    // CR-002 follow-up) so there is one place each table is written; behaviour
    // is unchanged and the verification matrix guards it
    private final MembershipStore membershipStore;
    private final PaymentStore paymentStore;

    ImportService(Jdbi jdbi) {
        this.jdbi = jdbi;
        this.membershipStore = new MembershipStore(jdbi);
        this.paymentStore = new PaymentStore(jdbi);
    }

    /** Parse, validate and dedup; writes nothing. */
    Report preview(String csv, String periodParam) {
        return jdbi.withHandle(handle -> {
            Validated v = validate(handle, csv, periodParam);
            return v.report(null);
        });
    }

    /**
     * Same validation as {@link #preview}; if it produced any error the
     * report comes back with {@code created == null} (the resource answers
     * 400 and nothing is written), otherwise the whole plan is applied in
     * one transaction and the report carries the created counts.
     */
    Report apply(String csv, String periodParam, String recordedBy) {
        return jdbi.inTransaction(handle -> {
            Validated v = validate(handle, csv, periodParam);
            if (!v.errors.isEmpty()) {
                return v.report(null); // no writes happened; rollback is a no-op
            }
            Counts created = applyWrites(handle, v.plan, recordedBy);
            return v.report(created);
        });
    }

    // ---- validation ---------------------------------------------------------

    /** Everything the two phases compute in common, plus the write plan. */
    private static final class Validated {
        int rows;
        final List<Issue> errors = new ArrayList<>();
        final List<Issue> warnings = new ArrayList<>();
        final List<Skip> skipped = new ArrayList<>();
        final List<GroupPlan> plan = new ArrayList<>(); // only creatable groups

        Report report(Counts created) {
            int people = plan.stream().mapToInt(g -> g.createdRows.size()).sum();
            int households = plan.size();
            int memberships = (int) plan.stream().filter(g -> g.membershipType != null).count();
            int payments = (int) plan.stream().filter(g -> g.membershipType != null && g.paid).count();
            return new Report(rows, errors, warnings, skipped,
                    new Counts(people, households, memberships, payments), created);
        }
    }

    /**
     * One parsed CSV data row, fields normalised. {@code valid} is false when
     * the row itself carried a blocking error (bad name, relationship, email,
     * phoneType or paid) — such a row is neither created nor counted, and its
     * whole household is excluded from the plan.
     */
    private record Row(long line, String household, String title, String given,
                       String family, String preferred, String relationship,
                       List<String> emails, String phone, String phoneType,
                       String line1, String line2, String locality, String state,
                       String postcode, String membershipType, Boolean paid, String notes,
                       boolean valid) {}

    /** A household group that WILL be created (valid, has one or more non-skipped rows). */
    private record GroupPlan(String householdName, List<Row> createdRows, Row addressRow,
                             String membershipType, long membershipTypeId, int amountDueCents,
                             boolean paid, Period period) {}

    private record Period(long id, String name, LocalDate start, LocalDate end) {}

    private Validated validate(Handle handle, String csv, String periodParam) {
        Validated v = new Validated();
        List<Row> rows;
        try {
            rows = parse(csv, v);
        } catch (RuntimeException e) {
            v.errors.add(new Issue(0, "could not parse CSV: " + e.getMessage()));
            return v;
        }
        v.rows = rows.size();
        if (rows.size() > MAX_ROWS) {
            v.errors.add(new Issue(0, "too many rows: " + rows.size() + " (max " + MAX_ROWS + ")"));
            return v;
        }

        withinFileWarnings(rows, v);

        // group by household label, first appearance order; a blank label makes
        // each such row its own household (keyed uniquely by line)
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : rows) {
            groups.computeIfAbsent(groupKey(row), k -> new ArrayList<>()).add(row);
        }

        Map<String, Long> typeIds = loadTypeIds(handle);

        for (List<Row> group : groups.values()) {
            validateGroup(handle, group, periodParam, typeIds, v);
        }
        return v;
    }

    /** Read the header, reject unknown columns, and map each record to a Row. */
    private List<Row> parse(String csv, Validated v) {
        String text = csv == null ? "" : csv;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1); // Excel UTF-8 BOM
        List<Row> rows = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true)
                .setTrim(true).setIgnoreEmptyLines(true).build();
        try (CSVParser parser = CSVParser.parse(text, format)) {
            Set<String> headers = new HashSet<>(parser.getHeaderMap().keySet());
            for (String header : headers) {
                if (!KNOWN_COLUMNS.contains(header)) {
                    v.errors.add(new Issue(1, "unknown column '" + header + "'"));
                }
            }
            if (!headers.contains("givenName") || !headers.contains("familyName")) {
                v.errors.add(new Issue(1, "givenName and familyName columns are required"));
                return rows; // can't meaningfully read rows without them
            }
            for (CSVRecord rec : parser) {
                rows.add(readRow(rec, v));
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        return rows;
    }

    private Row readRow(CSVRecord rec, Validated v) {
        long line = rec.getRecordNumber() + 1; // header is line 1
        boolean valid = true;
        String given = get(rec, "givenName");
        String family = get(rec, "familyName");
        if (given == null || family == null) {
            v.errors.add(new Issue(line, "givenName and familyName are required"));
            valid = false;
        }
        String relationship = get(rec, "relationship");
        relationship = relationship == null ? "MEMBER" : relationship.toUpperCase(Locale.ROOT);
        if (!RELATIONSHIP_TYPES.contains(relationship)) {
            v.errors.add(new Issue(line, "relationship must be one of " + RELATIONSHIP_TYPES));
            valid = false;
        }
        String phoneType = get(rec, "phoneType");
        if (phoneType != null) {
            phoneType = phoneType.toUpperCase(Locale.ROOT);
            if (!PHONE_TYPES.contains(phoneType)) {
                v.errors.add(new Issue(line, "phoneType must be one of " + PHONE_TYPES));
                valid = false;
            }
        }
        List<String> emails = new ArrayList<>();
        String rawEmail = get(rec, "email");
        if (rawEmail != null) {
            for (String part : rawEmail.split(";")) {
                String e = part.trim().toLowerCase(Locale.ROOT);
                if (e.isEmpty()) continue;
                if (!e.contains("@")) {
                    v.errors.add(new Issue(line, "email '" + part.trim() + "' must contain @"));
                    valid = false;
                } else {
                    emails.add(e);
                }
            }
        }
        Boolean paid = null;
        String rawPaid = get(rec, "paid");
        if (rawPaid != null) {
            paid = parsePaid(rawPaid);
            if (paid == null) {
                v.errors.add(new Issue(line, "paid must be yes/no (also y/n/true/false/1/0)"));
                valid = false;
            }
        }
        String membershipType = get(rec, "membershipType");
        if (membershipType != null) membershipType = membershipType.toUpperCase(Locale.ROOT);
        return new Row(line, get(rec, "household"), get(rec, "title"), given, family,
                get(rec, "preferredName"), relationship, emails,
                get(rec, "phone"), phoneType, get(rec, "line1"), get(rec, "line2"),
                get(rec, "locality"), get(rec, "state"), get(rec, "postcode"),
                membershipType, paid, get(rec, "notes"), valid);
    }

    /**
     * Validate a single household group and, if it survives, append its
     * {@link GroupPlan}. Any blocking error excludes the whole group from the
     * plan (a household is created whole or not at all).
     */
    private void validateGroup(Handle handle, List<Row> group, String periodParam,
                               Map<String, Long> typeIds, Validated v) {
        Row first = group.get(0);
        long line = first.line;
        boolean groupOk = group.stream().allMatch(Row::valid); // any bad row excludes the whole household

        // per-group agreement on membershipType and paid where set
        Agreement<String> typeAgreed = single(group, r -> r.membershipType);
        String membershipType = typeAgreed.value();
        if (typeAgreed.conflict()) {
            v.errors.add(new Issue(line, "rows in a household must agree on membershipType"));
            groupOk = false;
            membershipType = null;
        }
        Agreement<Boolean> paidAgreed = single(group, r -> r.paid);
        if (paidAgreed.conflict()) {
            v.errors.add(new Issue(line, "rows in a household must agree on paid"));
            groupOk = false;
        }
        boolean paid = Boolean.TRUE.equals(paidAgreed.value());

        // odd-but-legal composition is a warning: the human decides
        if ("SINGLE".equals(membershipType) && group.size() > 1) {
            v.warnings.add(new Issue(line, "membershipType SINGLE but the household has "
                    + group.size() + " people"));
        }
        if ("HOUSEHOLD".equals(membershipType) && group.size() == 1) {
            v.warnings.add(new Issue(line, "membershipType HOUSEHOLD but the household has 1 person"));
        }

        // dedup each row against the existing register
        List<Row> createdRows = new ArrayList<>();
        int skippedInGroup = 0;
        for (Row row : group) {
            if (!row.valid) continue; // already errored — not created, not skipped
            Optional<Match> match = findExisting(handle, row);
            if (match.isPresent()) {
                Match m = match.get();
                v.warnings.add(new Issue(row.line, m.reason()));
                v.skipped.add(new Skip(row.line, "existing person matched (#" + m.personId() + ")"));
                skippedInGroup++;
            } else {
                createdRows.add(row);
            }
        }
        // a partly-deduped household is the surprising case: v1 creates only, so
        // the matched people are NOT linked to this new household — say so loudly
        if (skippedInGroup > 0 && !createdRows.isEmpty()) {
            v.warnings.add(new Issue(line, "household has " + skippedInGroup
                    + " already-registered person(s) that will NOT be added to this new household"));
        }

        // membership needs a resolvable period and a price for the type
        long membershipTypeId = 0;
        int amountDueCents = 0;
        Period period = null;
        if (membershipType != null) {
            Long typeId = typeIds.get(membershipType);
            if (typeId == null) {
                v.errors.add(new Issue(line, "unknown membershipType '" + membershipType + "'"));
                groupOk = false;
            } else {
                period = resolvePeriod(handle, periodParam);
                if (period == null) {
                    v.errors.add(new Issue(line, periodParam == null
                            ? "no membership period covers today; pass ?period=NAME"
                            : "no membership period named '" + periodParam + "'"));
                    groupOk = false;
                } else {
                    Optional<Integer> price = priceCents(handle, typeId, period.id());
                    if (price.isEmpty()) {
                        v.errors.add(new Issue(line, "no price for membershipType " + membershipType
                                + " in period " + period.name()));
                        groupOk = false;
                    } else {
                        membershipTypeId = typeId;
                        amountDueCents = price.get();
                    }
                }
            }
        }

        if (groupOk && !createdRows.isEmpty()) {
            v.plan.add(new GroupPlan(householdName(first, createdRows.get(0)),
                    createdRows, first, membershipType, membershipTypeId,
                    amountDueCents, paid, period));
        }
    }

    // ---- apply --------------------------------------------------------------

    private Counts applyWrites(Handle handle, List<GroupPlan> plan, String recordedBy) {
        int people = 0, households = 0, memberships = 0, payments = 0;
        LocalDate today = handle.createQuery("SELECT current_date")
                .map((rs, ctx) -> rs.getDate(1).toLocalDate()).one();
        for (GroupPlan group : plan) {
            List<Long> personIds = new ArrayList<>();
            for (Row row : group.createdRows) {
                personIds.add(insertPerson(handle, row));
                people++;
            }
            long householdId = handle.createUpdate(
                    "INSERT INTO household (household_name, primary_contact_person_id)"
                    + " VALUES (:name, :primary)")
                    .bind("name", group.householdName)
                    .bind("primary", personIds.get(0))
                    .executeAndReturnGeneratedKeys("household_id").mapTo(Long.class).one();
            households++;
            for (int i = 0; i < group.createdRows.size(); i++) {
                handle.createUpdate(
                        "INSERT INTO household_person (household_id, person_id, relationship_type,"
                        + " joined_household_date) VALUES (:hh, :pid, :rel, current_date)")
                        .bind("hh", householdId).bind("pid", personIds.get(i))
                        .bind("rel", group.createdRows.get(i).relationship).execute();
            }
            insertAddress(handle, householdId, group.addressRow);

            if (group.membershipType != null) {
                // ACTIVE only once paid (approved on the import date); otherwise it
                // waits for payment, so paid-ness stays derivable from allocations
                String status = group.paid ? "ACTIVE" : "PENDING_PAYMENT";
                LocalDate approved = group.paid ? today : null;
                long membershipId = membershipStore.insertMembership(handle, group.period.id(),
                        group.membershipTypeId, householdId, status, today, approved,
                        group.period.start(), group.period.end(), group.amountDueCents);
                memberships++;
                for (int i = 0; i < group.createdRows.size(); i++) {
                    membershipStore.insertMembershipPerson(handle, membershipId, personIds.get(i),
                            group.createdRows.get(i).relationship);
                }
                if (group.paid) {
                    paymentStore.insertImportPayment(handle, membershipId, personIds.get(0),
                            group.amountDueCents, recordedBy);
                    payments++;
                }
            }
        }
        return new Counts(people, households, memberships, payments);
    }

    private long insertPerson(Handle handle, Row row) {
        long id = handle.createUpdate(
                "INSERT INTO person (title, given_name, family_name, preferred_name, notes)"
                + " VALUES (:title, :given, :family, :preferred, :notes)")
                .bind("title", row.title).bind("given", row.given).bind("family", row.family)
                .bind("preferred", row.preferred).bind("notes", row.notes)
                .executeAndReturnGeneratedKeys("person_id").mapTo(Long.class).one();
        for (int i = 0; i < row.emails.size(); i++) {
            handle.createUpdate("INSERT INTO email_address (person_id, email, is_primary)"
                    + " VALUES (:id, :email, :primary)")
                    .bind("id", id).bind("email", row.emails.get(i))
                    .bind("primary", i == 0).execute();
        }
        if (row.phone != null) {
            handle.createUpdate("INSERT INTO phone_number (person_id, number, phone_type, is_primary)"
                    + " VALUES (:id, :number, :type, true)")
                    .bind("id", id).bind("number", row.phone).bind("type", row.phoneType).execute();
        }
        return id;
    }

    private void insertAddress(Handle handle, long householdId, Row row) {
        if (row.line1 == null) return; // line_1 is NOT NULL — no address without it
        handle.createUpdate(
                "INSERT INTO household_address (household_id, address_type, line_1, line_2,"
                + " locality, state, postcode, valid_from, is_preferred)"
                + " VALUES (:hh, 'POSTAL', :l1, :l2, :loc, :st, :pc, current_date, true)")
                .bind("hh", householdId).bind("l1", row.line1).bind("l2", row.line2)
                .bind("loc", row.locality).bind("st", row.state).bind("pc", row.postcode).execute();
    }

    // ---- dedup and lookups --------------------------------------------------

    private record Match(long personId, String reason) {}

    /** A row matches an existing person by any email OR by given+family name. */
    private Optional<Match> findExisting(Handle handle, Row row) {
        for (String email : row.emails) {
            Optional<Match> byEmail = handle.createQuery(
                    "SELECT e.person_id, p.given_name, p.family_name FROM email_address e"
                    + " JOIN person p ON p.person_id = e.person_id WHERE e.email = :email"
                    + " ORDER BY e.person_id LIMIT 1")
                    .bind("email", email)
                    .map((rs, ctx) -> new Match(rs.getLong("person_id"),
                            "email " + email + " already belongs to person #" + rs.getLong("person_id")
                            + " (" + rs.getString("given_name") + " " + rs.getString("family_name") + ")"))
                    .findOne();
            if (byEmail.isPresent()) return byEmail;
        }
        return handle.createQuery(
                "SELECT person_id FROM person WHERE lower(given_name) = lower(:given)"
                + " AND lower(family_name) = lower(:family) ORDER BY person_id LIMIT 1")
                .bind("given", row.given).bind("family", row.family)
                .map((rs, ctx) -> new Match(rs.getLong("person_id"),
                        "name " + row.given + " " + row.family + " already belongs to person #"
                        + rs.getLong("person_id")))
                .findOne();
    }

    private Map<String, Long> loadTypeIds(Handle handle) {
        Map<String, Long> ids = new HashMap<>();
        handle.createQuery("SELECT membership_type_id, name FROM membership_type")
                .map((rs, ctx) -> Map.entry(rs.getString("name"), rs.getLong("membership_type_id")))
                .forEach(e -> ids.put(e.getKey(), e.getValue()));
        return ids;
    }

    private Period resolvePeriod(Handle handle, String periodParam) {
        var query = periodParam != null
                ? handle.createQuery("SELECT membership_period_id, name, start_date, end_date"
                        + " FROM membership_period WHERE name = :name").bind("name", periodParam)
                : handle.createQuery("SELECT membership_period_id, name, start_date, end_date"
                        + " FROM membership_period WHERE current_date BETWEEN start_date AND end_date"
                        + " ORDER BY start_date DESC LIMIT 1");
        return query.map((rs, ctx) -> new Period(rs.getLong("membership_period_id"),
                        rs.getString("name"), rs.getDate("start_date").toLocalDate(),
                        rs.getDate("end_date").toLocalDate()))
                .findOne().orElse(null);
    }

    private Optional<Integer> priceCents(Handle handle, long typeId, long periodId) {
        return handle.createQuery("SELECT amount_cents FROM membership_type_price"
                + " WHERE membership_type_id = :type AND membership_period_id = :period")
                .bind("type", typeId).bind("period", periodId)
                .mapTo(Integer.class).findOne();
    }

    // ---- within-file checks and small helpers -------------------------------

    /** Warnings that come from the file alone (no database needed). */
    private void withinFileWarnings(List<Row> rows, Validated v) {
        // the same email across two different household groups (couples share
        // an email WITHIN a household legitimately, so only cross-group counts)
        Map<String, Set<String>> emailGroups = new HashMap<>();
        Map<String, Long> emailFirstLine = new HashMap<>();
        for (Row row : rows) {
            for (String email : row.emails) {
                emailGroups.computeIfAbsent(email, k -> new HashSet<>()).add(groupKey(row));
                emailFirstLine.putIfAbsent(email, row.line);
            }
        }
        emailGroups.forEach((email, groupKeys) -> {
            if (groupKeys.size() > 1) {
                v.warnings.add(new Issue(emailFirstLine.get(email),
                        "email " + email + " appears in " + groupKeys.size() + " different households"));
            }
        });
        // the same given+family name twice in the file
        Map<String, Long> nameFirstLine = new LinkedHashMap<>();
        Map<String, Integer> nameCount = new HashMap<>();
        for (Row row : rows) {
            if (row.given == null || row.family == null) continue;
            String key = (row.given + " " + row.family).toLowerCase(Locale.ROOT);
            nameFirstLine.putIfAbsent(key, row.line);
            nameCount.merge(key, 1, Integer::sum);
        }
        nameCount.forEach((key, count) -> {
            if (count > 1) {
                v.warnings.add(new Issue(nameFirstLine.get(key),
                        "the name '" + key + "' appears " + count + " times in the file"));
            }
        });
    }

    /** Household grouping key: the label lowercased, or the line for a blank label. */
    private static String groupKey(Row row) {
        return row.household == null ? " " + row.line : row.household.toLowerCase(Locale.ROOT);
    }

    private static String householdName(Row first, Row primary) {
        if (first.household != null) return first.household;
        return (primary.given + " " + primary.family).trim(); // blank label → name the person
    }

    /** The value a group agrees on ({@code null} if none set) or a conflict. */
    private record Agreement<T>(T value, boolean conflict) {}

    /** The single non-null value the group's rows share, or a conflict. */
    private static <T> Agreement<T> single(List<Row> group, java.util.function.Function<Row, T> field) {
        T seen = null;
        for (Row row : group) {
            T value = field.apply(row);
            if (value == null) continue;
            if (seen == null) seen = value;
            else if (!seen.equals(value)) return new Agreement<>(null, true);
        }
        return new Agreement<>(seen, false);
    }

    private static Boolean parsePaid(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "yes", "y", "true", "1" -> Boolean.TRUE;
            case "no", "n", "false", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** A mapped, present, non-blank column value, else null. */
    private static String get(CSVRecord rec, String name) {
        if (!rec.isMapped(name) || !rec.isSet(name)) return null;
        String value = rec.get(name).trim();
        return value.isEmpty() ? null : value;
    }
}
