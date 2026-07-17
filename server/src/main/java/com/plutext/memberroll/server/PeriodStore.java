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
 * Membership periods and their per-type prices (CR-003). A period carries
 * one {@code membership_type_price} row per membership type — created together
 * so rollover can never fail later for want of a price (schema rule 10, made
 * structural). Prices are a snapshot: changing a period's price does NOT
 * touch existing memberships' {@code amount_due_cents} — that was frozen at
 * rollover/application time — unless {@code repriceUnpaid} explicitly
 * re-snapshots the still-unpaid ones (the "rolled over before noticing the
 * fee rise" recovery path).
 *
 * Read methods open their own handle; write methods take a caller's {@link
 * Handle} so a resource can compose them with the other stores in one
 * transaction (the CR-002 nested-transaction lesson).
 */
final class PeriodStore {

    record Price(long typeId, String typeName, int amountCents) {}
    record Period(long id, String name, LocalDate startDate, LocalDate endDate,
                  LocalDate renewalOpenDate, LocalDate lateJoiningCutoff,
                  List<Price> prices, Map<String, Integer> countsByStatus) {}

    private final Jdbi jdbi;

    PeriodStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /** All periods, newest first, each with its prices and status counts. */
    List<Period> list() {
        return jdbi.withHandle(handle -> {
            List<Period> bases = handle.createQuery(
                    "SELECT membership_period_id, name, start_date, end_date,"
                    + " renewal_open_date, late_joining_cutoff FROM membership_period"
                    + " ORDER BY start_date DESC, membership_period_id DESC")
                    .map((rs, ctx) -> mapPeriod(rs)).list();
            List<Period> full = new ArrayList<>(bases.size());
            for (Period p : bases) full.add(hydrate(handle, p));
            return full;
        });
    }

    Optional<Period> get(long id) {
        return jdbi.withHandle(handle -> get(handle, id));
    }

    static Optional<Period> get(Handle handle, long id) {
        return handle.createQuery(
                "SELECT membership_period_id, name, start_date, end_date,"
                + " renewal_open_date, late_joining_cutoff FROM membership_period"
                + " WHERE membership_period_id = :id")
                .bind("id", id)
                .map((rs, ctx) -> mapPeriod(rs))
                .findOne()
                .map(p -> hydrate(handle, p));
    }

    /**
     * Create a period with a price for EVERY membership type. A duplicate name
     * is a {@link ConflictException} (409); a type left without a price, or a
     * price for an unknown type, is an {@link IllegalArgumentException} (400) —
     * the prices are confirmed with the society at exactly the moment they are
     * typed in, so a gap is caught here, not at the next rollover.
     */
    Period create(Handle handle, String name, LocalDate start, LocalDate end,
                  LocalDate renewalOpen, LocalDate lateCutoff, Map<String, Integer> pricesByType) {
        if (handle.createQuery("SELECT count(*) FROM membership_period WHERE name = :name")
                .bind("name", name).mapTo(Integer.class).one() > 0) {
            throw new ConflictException("a period named '" + name + "' already exists");
        }
        Map<String, Long> types = loadTypeIds(handle);
        requirePriceForEveryType(types, pricesByType);
        long id = handle.createUpdate(
                "INSERT INTO membership_period (name, start_date, end_date, renewal_open_date, late_joining_cutoff)"
                + " VALUES (:name, :start, :end, :open, :cutoff)")
                .bind("name", name).bind("start", start).bind("end", end)
                .bind("open", renewalOpen).bind("cutoff", lateCutoff)
                .executeAndReturnGeneratedKeys("membership_period_id").mapTo(Long.class).one();
        types.forEach((typeName, typeId) ->
                insertPrice(handle, typeId, id, pricesByType.get(typeName)));
        return get(handle, id).orElseThrow();
    }

    /**
     * Update dates and prices; empty if no such period. Provided prices are
     * upserted per type (a period may gain a price for a newly-added type).
     * Existing memberships' {@code amount_due_cents} is left alone unless
     * {@code repriceUnpaid} — then every membership of the period with NO
     * payment allocations is re-snapshotted to the current price of its type
     * (part-paid memberships stay frozen; repricing under money is drift).
     */
    Optional<Period> update(Handle handle, long id, LocalDate start, LocalDate end,
                            LocalDate renewalOpen, LocalDate lateCutoff,
                            Map<String, Integer> pricesByType, boolean repriceUnpaid) {
        int updated = handle.createUpdate(
                "UPDATE membership_period SET start_date = :start, end_date = :end,"
                + " renewal_open_date = :open, late_joining_cutoff = :cutoff"
                + " WHERE membership_period_id = :id")
                .bind("id", id).bind("start", start).bind("end", end)
                .bind("open", renewalOpen).bind("cutoff", lateCutoff)
                .execute();
        if (updated == 0) return Optional.empty();
        Map<String, Long> types = loadTypeIds(handle);
        pricesByType.forEach((typeName, cents) -> {
            Long typeId = types.get(typeName.toUpperCase(java.util.Locale.ROOT));
            if (typeId == null) throw new IllegalArgumentException("unknown membership type '" + typeName + "'");
            int rows = handle.createUpdate("UPDATE membership_type_price SET amount_cents = :cents"
                    + " WHERE membership_type_id = :type AND membership_period_id = :period")
                    .bind("cents", cents).bind("type", typeId).bind("period", id).execute();
            if (rows == 0) insertPrice(handle, typeId, id, cents);
        });
        if (repriceUnpaid) {
            handle.createUpdate(
                    "UPDATE membership m SET amount_due_cents = ("
                    + "  SELECT tp.amount_cents FROM membership_type_price tp"
                    + "  WHERE tp.membership_type_id = m.membership_type_id"
                    + "    AND tp.membership_period_id = m.membership_period_id)"
                    + " WHERE m.membership_period_id = :period"
                    + "   AND NOT EXISTS (SELECT 1 FROM payment_allocation pa"
                    + "                   WHERE pa.membership_id = m.membership_id)")
                    .bind("period", id).execute();
        }
        return get(handle, id);
    }

    /** The price for a type in a period, if one is set. */
    static Optional<Integer> priceCents(Handle handle, long typeId, long periodId) {
        return handle.createQuery("SELECT amount_cents FROM membership_type_price"
                + " WHERE membership_type_id = :type AND membership_period_id = :period")
                .bind("type", typeId).bind("period", periodId)
                .mapTo(Integer.class).findOne();
    }

    /** Name -> id for every membership type. */
    static Map<String, Long> loadTypeIds(Handle handle) {
        Map<String, Long> ids = new LinkedHashMap<>();
        handle.createQuery("SELECT membership_type_id, name FROM membership_type ORDER BY name")
                .map((rs, ctx) -> Map.entry(rs.getString("name"), rs.getLong("membership_type_id")))
                .forEach(e -> ids.put(e.getKey(), e.getValue()));
        return ids;
    }

    // ---- helpers ------------------------------------------------------------

    private static void requirePriceForEveryType(Map<String, Long> types, Map<String, Integer> pricesByType) {
        for (String provided : pricesByType.keySet()) {
            if (!types.containsKey(provided.toUpperCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("unknown membership type '" + provided + "'");
            }
        }
        for (String typeName : types.keySet()) {
            if (pricesByType.get(typeName) == null) {
                throw new IllegalArgumentException("missing price for membership type '" + typeName + "'");
            }
        }
    }

    private static void insertPrice(Handle handle, long typeId, long periodId, int cents) {
        handle.createUpdate("INSERT INTO membership_type_price (membership_type_id, membership_period_id, amount_cents)"
                + " VALUES (:type, :period, :cents)")
                .bind("type", typeId).bind("period", periodId).bind("cents", cents).execute();
    }

    private static Period hydrate(Handle handle, Period p) {
        List<Price> prices = handle.createQuery(
                "SELECT tp.membership_type_id, mt.name, tp.amount_cents"
                + " FROM membership_type_price tp JOIN membership_type mt"
                + "   ON mt.membership_type_id = tp.membership_type_id"
                + " WHERE tp.membership_period_id = :period ORDER BY mt.name")
                .bind("period", p.id())
                .map((rs, ctx) -> new Price(rs.getLong("membership_type_id"),
                        rs.getString("name"), rs.getInt("amount_cents"))).list();
        Map<String, Integer> counts = new LinkedHashMap<>();
        handle.createQuery("SELECT status, count(*) AS n FROM membership"
                + " WHERE membership_period_id = :period GROUP BY status ORDER BY status")
                .bind("period", p.id())
                .map((rs, ctx) -> Map.entry(rs.getString("status"), rs.getInt("n")))
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return new Period(p.id(), p.name(), p.startDate(), p.endDate(),
                p.renewalOpenDate(), p.lateJoiningCutoff(), prices, counts);
    }

    private static Period mapPeriod(ResultSet rs) {
        try {
            return new Period(rs.getLong("membership_period_id"), rs.getString("name"),
                    localDate(rs, "start_date"), localDate(rs, "end_date"),
                    localDate(rs, "renewal_open_date"), localDate(rs, "late_joining_cutoff"),
                    List.of(), Map.of());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }
}
