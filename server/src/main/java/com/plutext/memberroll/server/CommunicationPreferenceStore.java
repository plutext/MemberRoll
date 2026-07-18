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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The communication_preference table (CR-001's table, first written to in
 * CR-005). The applicable preference for a person and a communication type is
 * resolved person → household → the default EMAIL (decided 2026-07-18) — the
 * one place segment mail decides who to email, who to skip as post, and who
 * has asked for nothing. Writes are history-preserving (the insert-don't-
 * overwrite house rule): a change CLOSES the current row (effective_to =
 * today) and INSERTS the new one; "current" is the row with a NULL
 * effective_to. consent_status stays untouched (NULL) — it exists for CR-007's
 * opt-in flows and writing it here would invent semantics early.
 *
 * All methods take the caller's {@link Handle}: the resource owns the
 * transaction, matching the CR-003 store pattern.
 */
final class CommunicationPreferenceStore {

    static final Set<String> COMMUNICATION_TYPES = Set.of("NEWSLETTER", "RENEWAL", "EVENTS", "GENERAL");
    static final Set<String> DELIVERY_METHODS = Set.of("EMAIL", "POST", "SMS", "NONE");
    static final String DEFAULT_METHOD = "EMAIL";

    /** The effective delivery method for a type, and where it came from (for the greyed-defaults UI). */
    record Resolved(String method, String source) {}   // source: person | household | default

    private CommunicationPreferenceStore() {}

    // ---- resolution ---------------------------------------------------------

    /**
     * The applicable delivery method for a person on a communication type:
     * their current row if any, else their household's current row, else the
     * default EMAIL. This is the segment-mail decision function.
     */
    static String resolve(Handle handle, long personId, long householdId, String communicationType) {
        String person = currentMethod(handle, "person_id", personId, communicationType);
        if (person != null) return person;
        String household = currentMethod(handle, "household_id", householdId, communicationType);
        if (household != null) return household;
        return DEFAULT_METHOD;
    }

    /**
     * The four types resolved for a person (person → household → default), for
     * GET. {@code householdId} is nullable — a person who belongs to no
     * current household simply inherits nothing and falls to the EMAIL default.
     */
    static Map<String, Resolved> forPerson(Handle handle, long personId, Long householdId) {
        Map<String, Resolved> out = new LinkedHashMap<>();
        for (String type : orderedTypes()) {
            String person = currentMethod(handle, "person_id", personId, type);
            if (person != null) {
                out.put(type, new Resolved(person, "person"));
                continue;
            }
            String household = householdId == null ? null
                    : currentMethod(handle, "household_id", householdId, type);
            out.put(type, household != null
                    ? new Resolved(household, "household") : new Resolved(DEFAULT_METHOD, "default"));
        }
        return out;
    }

    /** The four types resolved for a household (household → default), for GET. */
    static Map<String, Resolved> forHousehold(Handle handle, long householdId) {
        Map<String, Resolved> out = new LinkedHashMap<>();
        for (String type : orderedTypes()) {
            String household = currentMethod(handle, "household_id", householdId, type);
            out.put(type, household != null
                    ? new Resolved(household, "household") : new Resolved(DEFAULT_METHOD, "default"));
        }
        return out;
    }

    // ---- writes (close current, insert new; no churn when nothing changes) ---

    /**
     * Set a person's preference. Inherited = the household's current row for
     * this type, else EMAIL. Setting the value that equals the inherited one
     * inserts nothing (and closes any explicit override, reverting to
     * inheritance) — no row churn for the common "already the default" case.
     */
    static void putPerson(Handle handle, long personId, Long householdId, String type, String method) {
        String inherited = householdId == null ? DEFAULT_METHOD
                : currentMethodOrDefault(handle, "household_id", householdId, type);
        String current = currentMethod(handle, "person_id", personId, type);
        if (method.equals(inherited)) {
            if (current != null) closeCurrent(handle, "person_id", personId, type); // back to inheritance
            return;
        }
        if (method.equals(current)) return; // already set to exactly this
        if (current != null) closeCurrent(handle, "person_id", personId, type);
        insert(handle, "person_id", personId, type, method);
    }

    /** Set a household's preference. Inherited is the bare default EMAIL (households have no parent). */
    static void putHousehold(Handle handle, long householdId, String type, String method) {
        String current = currentMethod(handle, "household_id", householdId, type);
        if (method.equals(DEFAULT_METHOD)) {
            if (current != null) closeCurrent(handle, "household_id", householdId, type);
            return;
        }
        if (method.equals(current)) return;
        if (current != null) closeCurrent(handle, "household_id", householdId, type);
        insert(handle, "household_id", householdId, type, method);
    }

    // ---- existence (for 404) ------------------------------------------------

    static boolean personExists(Handle handle, long personId) {
        return handle.createQuery("SELECT count(*) FROM person WHERE person_id = :id")
                .bind("id", personId).mapTo(Integer.class).one() > 0;
    }

    static boolean householdExists(Handle handle, long householdId) {
        return handle.createQuery("SELECT count(*) FROM household WHERE household_id = :id")
                .bind("id", householdId).mapTo(Integer.class).one() > 0;
    }

    /** The household a person currently belongs to, if any — the parent for person-level resolution. */
    static Long currentHouseholdOf(Handle handle, long personId) {
        return handle.createQuery("SELECT household_id FROM household_person"
                + " WHERE person_id = :id AND left_household_date IS NULL"
                + " ORDER BY household_person_id DESC LIMIT 1")
                .bind("id", personId).mapTo(Long.class).findOne().orElse(null);
    }

    // ---- internals ----------------------------------------------------------

    private static String currentMethod(Handle handle, String column, long id, String type) {
        // ":owner" is a column name, not a bind — it is one of two literals this
        // class controls (person_id / household_id), never user input
        return handle.createQuery("SELECT delivery_method FROM communication_preference"
                + " WHERE " + column + " = :id AND communication_type = :type AND effective_to IS NULL"
                + " ORDER BY communication_preference_id DESC LIMIT 1")
                .bind("id", id).bind("type", type)
                .mapTo(String.class).findOne().orElse(null);
    }

    private static String currentMethodOrDefault(Handle handle, String column, long id, String type) {
        String m = currentMethod(handle, column, id, type);
        return m != null ? m : DEFAULT_METHOD;
    }

    private static void closeCurrent(Handle handle, String column, long id, String type) {
        handle.createUpdate("UPDATE communication_preference SET effective_to = current_date"
                + " WHERE " + column + " = :id AND communication_type = :type AND effective_to IS NULL")
                .bind("id", id).bind("type", type).execute();
    }

    private static void insert(Handle handle, String column, long id, String type, String method) {
        handle.createUpdate("INSERT INTO communication_preference (" + column + ", communication_type,"
                + " delivery_method, effective_from) VALUES (:id, :type, :method, current_date)")
                .bind("id", id).bind("type", type).bind("method", method).execute();
    }

    private static java.util.List<String> orderedTypes() {
        return java.util.List.of("NEWSLETTER", "RENEWAL", "EVENTS", "GENERAL");
    }
}
