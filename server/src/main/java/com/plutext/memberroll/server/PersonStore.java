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
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * People and their contact details (email_address, phone_number rows ride
 * along inside the person payload). Hand-written SQL over JDBI's fluent
 * API — the store IS the porting surface if anyone ever swaps engines.
 *
 * People are never deleted (schema business rule 7): no delete method on
 * purpose. Contact-detail updates are reconcile-by-replace inside the
 * update transaction — at one-payload-owns-its-children granularity
 * there is nothing to merge.
 */
final class PersonStore {

    record Email(String email, boolean isPrimary) {}
    record Phone(String number, String type, boolean isPrimary) {}
    /** {@code memberNo} is the CR-020 display number — null means the card shows the person id. */
    record Person(long id, String title, String givenName, String familyName,
                  String preferredName, LocalDate dateOfBirth, LocalDate deceasedDate,
                  String notes, Integer memberNo, List<Email> emails, List<Phone> phones) {}
    record Page(List<Person> people, int total) {}

    private static final String COLS =
            "person_id, title, given_name, family_name, preferred_name, "
            + "date_of_birth, deceased_date, notes, member_no";

    private final Jdbi jdbi;

    PersonStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    Page search(String q, int limit, int offset) {
        boolean filtered = q != null && !q.isBlank();
        // matches name fields, or any of the person's email addresses
        // (stored lowercase, so the email side compares lowercased)
        String where = filtered
                ? " WHERE given_name ILIKE :pat OR family_name ILIKE :pat"
                  + " OR preferred_name ILIKE :pat"
                  + " OR EXISTS (SELECT 1 FROM email_address e WHERE e.person_id = person.person_id"
                  + "            AND e.email LIKE :emailPat)"
                : "";
        return jdbi.withHandle(handle -> {
            var count = handle.createQuery("SELECT count(*) FROM person" + where);
            var page = handle.createQuery("SELECT " + COLS + " FROM person" + where
                    + " ORDER BY family_name, given_name, person_id LIMIT :limit OFFSET :offset")
                    .bind("limit", limit).bind("offset", offset);
            if (filtered) {
                String pattern = "%" + escapeLike(q.trim()) + "%";
                count.bind("pat", pattern).bind("emailPat", pattern.toLowerCase(Locale.ROOT));
                page.bind("pat", pattern).bind("emailPat", pattern.toLowerCase(Locale.ROOT));
            }
            int total = count.mapTo(Integer.class).one();
            List<Person> people = page.map((rs, ctx) -> mapPerson(rs)).list();
            return new Page(attachContacts(handle, people), total);
        });
    }

    Optional<Person> get(long id) {
        return jdbi.withHandle(handle -> get(handle, id));
    }

    /** Insert person + contact rows; the draft's id is ignored. */
    Person create(Person draft) {
        return jdbi.inTransaction(handle -> create(handle, draft));
    }

    /** Handle-taking variant (CR-010): lets a caller compose this into a larger transaction. */
    Person create(Handle handle, Person draft) {
        long id;
        try {
            id = handle.createUpdate(
                    "INSERT INTO person (title, given_name, family_name, preferred_name,"
                    + " date_of_birth, deceased_date, notes, member_no)"
                    + " VALUES (:title, :given, :family, :preferred, :dob, :deceased, :notes, :memberNo)")
                    .bind("title", draft.title())
                    .bind("given", draft.givenName())
                    .bind("family", draft.familyName())
                    .bind("preferred", draft.preferredName())
                    .bind("dob", draft.dateOfBirth())
                    .bind("deceased", draft.deceasedDate())
                    .bind("notes", draft.notes())
                    .bind("memberNo", draft.memberNo())
                    .executeAndReturnGeneratedKeys("person_id")
                    .mapTo(Long.class).one();
        } catch (UnableToExecuteStatementException e) {
            throw memberNoConflict(e, draft.memberNo());
        }
        insertContacts(handle, id, draft);
        return get(handle, id).orElseThrow();
    }

    /** Full-payload update; empty if no such person. */
    Optional<Person> update(long id, Person draft) {
        return jdbi.inTransaction(handle -> {
            int updated;
            try {
                updated = handle.createUpdate(
                        "UPDATE person SET title = :title, given_name = :given,"
                        + " family_name = :family, preferred_name = :preferred,"
                        + " date_of_birth = :dob, deceased_date = :deceased, notes = :notes,"
                        + " member_no = :memberNo"
                        + " WHERE person_id = :id")
                        .bind("id", id)
                        .bind("title", draft.title())
                        .bind("given", draft.givenName())
                        .bind("family", draft.familyName())
                        .bind("preferred", draft.preferredName())
                        .bind("dob", draft.dateOfBirth())
                        .bind("deceased", draft.deceasedDate())
                        .bind("notes", draft.notes())
                        .bind("memberNo", draft.memberNo())
                        .execute();
            } catch (UnableToExecuteStatementException e) {
                throw memberNoConflict(e, draft.memberNo());
            }
            if (updated == 0) return Optional.empty();
            handle.execute("DELETE FROM email_address WHERE person_id = ?", id);
            handle.execute("DELETE FROM phone_number WHERE person_id = ?", id);
            insertContacts(handle, id, draft);
            return get(handle, id);
        });
    }

    private static Optional<Person> get(Handle handle, long id) {
        return handle.createQuery("SELECT " + COLS + " FROM person WHERE person_id = :id")
                .bind("id", id)
                .map((rs, ctx) -> mapPerson(rs))
                .findOne()
                .map(person -> attachContacts(handle, List.of(person)).get(0));
    }

    private static void insertContacts(Handle handle, long id, Person draft) {
        for (Email email : draft.emails()) {
            handle.createUpdate("INSERT INTO email_address (person_id, email, is_primary)"
                    + " VALUES (:id, :email, :primary)")
                    .bind("id", id)
                    .bind("email", email.email().toLowerCase(Locale.ROOT))
                    .bind("primary", email.isPrimary())
                    .execute();
        }
        for (Phone phone : draft.phones()) {
            handle.createUpdate("INSERT INTO phone_number (person_id, number, phone_type, is_primary)"
                    + " VALUES (:id, :number, :type, :primary)")
                    .bind("id", id)
                    .bind("number", phone.number())
                    .bind("type", phone.type())
                    .bind("primary", phone.isPrimary())
                    .execute();
        }
    }

    private static List<Person> attachContacts(Handle handle, List<Person> people) {
        if (people.isEmpty()) return people;
        List<Long> ids = people.stream().map(Person::id).toList();
        Map<Long, List<Email>> emails = new HashMap<>();
        handle.createQuery("SELECT person_id, email, is_primary FROM email_address"
                + " WHERE person_id IN (<ids>) ORDER BY email_id")
                .bindList("ids", ids)
                .map((rs, ctx) -> Map.entry(rs.getLong("person_id"),
                        new Email(rs.getString("email"), rs.getBoolean("is_primary"))))
                .forEach(e -> emails.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        Map<Long, List<Phone>> phones = new HashMap<>();
        handle.createQuery("SELECT person_id, number, phone_type, is_primary FROM phone_number"
                + " WHERE person_id IN (<ids>) ORDER BY phone_number_id")
                .bindList("ids", ids)
                .map((rs, ctx) -> Map.entry(rs.getLong("person_id"),
                        new Phone(rs.getString("number"), rs.getString("phone_type"), rs.getBoolean("is_primary"))))
                .forEach(e -> phones.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return people.stream().map(p -> new Person(p.id(), p.title(), p.givenName(),
                p.familyName(), p.preferredName(), p.dateOfBirth(), p.deceasedDate(), p.notes(),
                p.memberNo(),
                emails.getOrDefault(p.id(), List.of()),
                phones.getOrDefault(p.id(), List.of()))).toList();
    }

    private static Person mapPerson(ResultSet rs) {
        try {
            return new Person(rs.getLong("person_id"), rs.getString("title"),
                    rs.getString("given_name"), rs.getString("family_name"),
                    rs.getString("preferred_name"),
                    localDate(rs, "date_of_birth"), localDate(rs, "deceased_date"),
                    rs.getString("notes"), rs.getObject("member_no", Integer.class),
                    List.of(), List.of());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The CR-020 unique index tripping is the one anticipated write failure on
     * this table: surface it as the store's 409 idiom (the friendly message
     * ahead of the constraint). Anything else re-throws untouched.
     */
    private static RuntimeException memberNoConflict(UnableToExecuteStatementException e, Integer memberNo) {
        if (e.getCause() instanceof SQLException sql && "23505".equals(sql.getSQLState())
                && sql.getMessage() != null && sql.getMessage().contains("person_member_no")) {
            return new ConflictException("member number " + memberNo
                    + " is already assigned to another person");
        }
        return e;
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }

    static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
