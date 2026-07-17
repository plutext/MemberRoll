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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Households — the billing unit. Composition history is preserved:
 * "removing" a member sets left_household_date (the row stays), and a
 * person can leave and rejoin (a second row). The primary contact lives
 * only on the household row and must always be a current member, which
 * is why removal of the primary contact is refused rather than cascaded.
 */
final class HouseholdStore {

    record Member(long personId, String givenName, String familyName,
                  String relationshipType, LocalDate joinedDate, LocalDate leftDate) {}
    record Household(long id, String name, long primaryContactPersonId,
                     String status, List<Member> members) {}
    record Summary(long id, String name, long primaryContactPersonId,
                   String primaryContactName, String status, int currentMembers) {}
    record Page(List<Summary> households, int total) {}

    enum AddResult { ADDED, HOUSEHOLD_NOT_FOUND, PERSON_NOT_FOUND, ALREADY_MEMBER }
    enum RemoveResult { REMOVED, NOT_A_CURRENT_MEMBER, IS_PRIMARY_CONTACT }

    private final Jdbi jdbi;

    HouseholdStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    Page search(String q, int limit, int offset) {
        boolean filtered = q != null && !q.isBlank();
        // matches the household name, or the name of any member past or present
        String where = filtered
                ? " WHERE h.household_name ILIKE :pat"
                  + " OR EXISTS (SELECT 1 FROM household_person hp JOIN person m ON m.person_id = hp.person_id"
                  + "            WHERE hp.household_id = h.household_id"
                  + "            AND (m.given_name ILIKE :pat OR m.family_name ILIKE :pat))"
                : "";
        return jdbi.withHandle(handle -> {
            var count = handle.createQuery("SELECT count(*) FROM household h" + where);
            var page = handle.createQuery(
                    "SELECT h.household_id, h.household_name, h.status, h.primary_contact_person_id,"
                    + " trim(p.given_name || ' ' || p.family_name) AS primary_contact_name,"
                    + " (SELECT count(*) FROM household_person hp WHERE hp.household_id = h.household_id"
                    + "  AND hp.left_household_date IS NULL) AS current_members"
                    + " FROM household h JOIN person p ON p.person_id = h.primary_contact_person_id"
                    + where + " ORDER BY h.household_name NULLS LAST, h.household_id"
                    + " LIMIT :limit OFFSET :offset")
                    .bind("limit", limit).bind("offset", offset);
            if (filtered) {
                String pattern = "%" + PersonStore.escapeLike(q.trim()) + "%";
                count.bind("pat", pattern);
                page.bind("pat", pattern);
            }
            int total = count.mapTo(Integer.class).one();
            List<Summary> households = page.map((rs, ctx) -> new Summary(
                    rs.getLong("household_id"), rs.getString("household_name"),
                    rs.getLong("primary_contact_person_id"), rs.getString("primary_contact_name"),
                    rs.getString("status"), rs.getInt("current_members"))).list();
            return new Page(households, total);
        });
    }

    Optional<Household> get(long id) {
        return jdbi.withHandle(handle -> get(handle, id));
    }

    /**
     * Create a household with its primary contact as first (MEMBER) row;
     * empty if the person does not exist.
     */
    Optional<Household> create(String name, long primaryContactPersonId) {
        return jdbi.inTransaction(handle -> {
            if (!personExists(handle, primaryContactPersonId)) return Optional.empty();
            long id = handle.createUpdate(
                    "INSERT INTO household (household_name, primary_contact_person_id)"
                    + " VALUES (:name, :contact)")
                    .bind("name", name).bind("contact", primaryContactPersonId)
                    .executeAndReturnGeneratedKeys("household_id")
                    .mapTo(Long.class).one();
            handle.createUpdate(
                    "INSERT INTO household_person (household_id, person_id, relationship_type, joined_household_date)"
                    + " VALUES (:household, :person, 'MEMBER', current_date)")
                    .bind("household", id).bind("person", primaryContactPersonId)
                    .execute();
            return get(handle, id);
        });
    }

    /**
     * Rename and/or reassign the primary contact; empty if no such
     * household. The new primary contact must already be a current member
     * (IllegalArgumentException otherwise — the caller maps it to 400).
     */
    Optional<Household> update(long id, String name, long primaryContactPersonId) {
        return jdbi.inTransaction(handle -> {
            if (handle.createQuery("SELECT count(*) FROM household WHERE household_id = :id")
                    .bind("id", id).mapTo(Integer.class).one() == 0) {
                return Optional.empty();
            }
            if (!isCurrentMember(handle, id, primaryContactPersonId)) {
                throw new IllegalArgumentException("primary contact must be a current member of the household");
            }
            handle.createUpdate("UPDATE household SET household_name = :name,"
                    + " primary_contact_person_id = :contact WHERE household_id = :id")
                    .bind("id", id).bind("name", name).bind("contact", primaryContactPersonId)
                    .execute();
            return get(handle, id);
        });
    }

    AddResult addPerson(long householdId, long personId, String relationshipType) {
        return jdbi.inTransaction(handle -> {
            if (handle.createQuery("SELECT count(*) FROM household WHERE household_id = :id")
                    .bind("id", householdId).mapTo(Integer.class).one() == 0) {
                return AddResult.HOUSEHOLD_NOT_FOUND;
            }
            if (!personExists(handle, personId)) return AddResult.PERSON_NOT_FOUND;
            if (isCurrentMember(handle, householdId, personId)) return AddResult.ALREADY_MEMBER;
            handle.createUpdate(
                    "INSERT INTO household_person (household_id, person_id, relationship_type, joined_household_date)"
                    + " VALUES (:household, :person, :type, current_date)")
                    .bind("household", householdId).bind("person", personId)
                    .bind("type", relationshipType)
                    .execute();
            return AddResult.ADDED;
        });
    }

    RemoveResult removePerson(long householdId, long personId) {
        return jdbi.inTransaction(handle -> {
            boolean isPrimary = handle.createQuery(
                    "SELECT count(*) FROM household WHERE household_id = :household"
                    + " AND primary_contact_person_id = :person")
                    .bind("household", householdId).bind("person", personId)
                    .mapTo(Integer.class).one() > 0;
            if (isPrimary) return RemoveResult.IS_PRIMARY_CONTACT;
            int updated = handle.createUpdate(
                    "UPDATE household_person SET left_household_date = current_date"
                    + " WHERE household_id = :household AND person_id = :person"
                    + " AND left_household_date IS NULL")
                    .bind("household", householdId).bind("person", personId)
                    .execute();
            return updated > 0 ? RemoveResult.REMOVED : RemoveResult.NOT_A_CURRENT_MEMBER;
        });
    }

    private static Optional<Household> get(Handle handle, long id) {
        return handle.createQuery(
                "SELECT household_id, household_name, primary_contact_person_id, status"
                + " FROM household WHERE household_id = :id")
                .bind("id", id)
                .map((rs, ctx) -> new Household(rs.getLong("household_id"),
                        rs.getString("household_name"), rs.getLong("primary_contact_person_id"),
                        rs.getString("status"), List.<Member>of()))
                .findOne()
                .map(h -> new Household(h.id(), h.name(), h.primaryContactPersonId(), h.status(),
                        handle.createQuery(
                                "SELECT hp.person_id, p.given_name, p.family_name, hp.relationship_type,"
                                + " hp.joined_household_date, hp.left_household_date"
                                + " FROM household_person hp JOIN person p ON p.person_id = hp.person_id"
                                + " WHERE hp.household_id = :id"
                                + " ORDER BY hp.left_household_date IS NOT NULL, hp.joined_household_date, hp.household_person_id")
                                .bind("id", h.id())
                                .map((rs, ctx) -> new Member(rs.getLong("person_id"),
                                        rs.getString("given_name"), rs.getString("family_name"),
                                        rs.getString("relationship_type"),
                                        rs.getDate("joined_household_date").toLocalDate(),
                                        rs.getDate("left_household_date") == null
                                                ? null : rs.getDate("left_household_date").toLocalDate()))
                                .list()));
    }

    private static boolean personExists(Handle handle, long personId) {
        return handle.createQuery("SELECT count(*) FROM person WHERE person_id = :id")
                .bind("id", personId).mapTo(Integer.class).one() > 0;
    }

    private static boolean isCurrentMember(Handle handle, long householdId, long personId) {
        return handle.createQuery("SELECT count(*) FROM household_person"
                + " WHERE household_id = :household AND person_id = :person"
                + " AND left_household_date IS NULL")
                .bind("household", householdId).bind("person", personId)
                .mapTo(Integer.class).one() > 0;
    }
}
