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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Payments and their allocations (CR-003). Insert-only bookkeeping: there is
 * no UPDATE or DELETE, ever — a mistake is corrected by an equal-and-opposite
 * payment (the {@code amount_cents <> 0} CHECK deliberately admits negatives),
 * so both entries keep their recorded_by/recorded_at audit trail. Only
 * MEMBERSHIP allocations count toward a membership fee; JOURNAL/DONATION/OTHER
 * ride the same payment without inflating paid-ness. The caller recomputes
 * every touched membership in the same transaction (see {@link #insert}).
 */
final class PaymentStore {

    record Allocation(long id, String type, Long membershipId, String householdName, int amountCents) {}
    record Payment(long id, LocalDate receivedDate, int amountCents, String method, Long payerPersonId,
                   String bankReference, String recordedBy, String recordedAt, String notes,
                   List<Allocation> allocations) {}
    record Page(List<Payment> payments, int total) {}

    /** A requested allocation line (membershipId required only for MEMBERSHIP). */
    record AllocationInput(String type, Long membershipId, int amountCents) {}
    /** The payment id plus the distinct memberships whose paid-ness moved. */
    record InsertResult(long paymentId, List<Long> touchedMembershipIds) {}

    private final Jdbi jdbi;

    PaymentStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    // ---- writes -------------------------------------------------------------

    /**
     * Insert a payment and its allocations in the caller's transaction. 400
     * (IllegalArgumentException) unless the allocations sum exactly to the
     * payment amount and every MEMBERSHIP line names a membership. Returns the
     * memberships the caller must {@link MembershipStore#recompute}.
     */
    InsertResult insert(Handle handle, LocalDate receivedDate, int amountCents, String method,
                        Long payerPersonId, String bankReference, String externalTransactionId,
                        String notes, String recordedBy, List<AllocationInput> allocations) {
        if (allocations.isEmpty()) throw new IllegalArgumentException("at least one allocation is required");
        long sum = 0;
        for (AllocationInput a : allocations) {
            sum += a.amountCents();
            if ("MEMBERSHIP".equals(a.type()) && a.membershipId() == null) {
                throw new IllegalArgumentException("a MEMBERSHIP allocation must name a membership");
            }
        }
        if (sum != amountCents) {
            throw new IllegalArgumentException("allocations sum (" + sum
                    + ") must equal the payment amount (" + amountCents + ")");
        }
        long paymentId = handle.createUpdate(
                "INSERT INTO payment (received_date, amount_cents, payment_method, payer_person_id,"
                + " bank_reference, external_transaction_id, recorded_by, notes)"
                + " VALUES (:d, :amt, :method, :payer, :ref, :ext, :by, :notes)")
                .bind("d", receivedDate).bind("amt", amountCents).bind("method", method)
                .bind("payer", payerPersonId).bind("ref", bankReference)
                .bind("ext", externalTransactionId).bind("by", recordedBy)
                .bind("notes", notes)
                .executeAndReturnGeneratedKeys("payment_id").mapTo(Long.class).one();
        LinkedHashSet<Long> touched = new LinkedHashSet<>();
        for (AllocationInput a : allocations) {
            handle.createUpdate("INSERT INTO payment_allocation (payment_id, allocation_type,"
                    + " membership_id, amount_cents) VALUES (:pay, :type, :mid, :amt)")
                    .bind("pay", paymentId).bind("type", a.type())
                    .bind("mid", a.membershipId()).bind("amt", a.amountCents()).execute();
            if ("MEMBERSHIP".equals(a.type())) touched.add(a.membershipId());
        }
        return new InsertResult(paymentId, new ArrayList<>(touched));
    }

    /** The full-fee membership payment the CSV import records (method OTHER, dated today). */
    void insertImportPayment(Handle handle, long membershipId, long payerPersonId,
                             int amountCents, String recordedBy) {
        long paymentId = handle.createUpdate(
                "INSERT INTO payment (received_date, amount_cents, payment_method, payer_person_id,"
                + " recorded_by, notes) VALUES (current_date, :amt, 'OTHER', :payer, :by, 'CSV import')")
                .bind("amt", amountCents).bind("payer", payerPersonId).bind("by", recordedBy)
                .executeAndReturnGeneratedKeys("payment_id").mapTo(Long.class).one();
        handle.createUpdate("INSERT INTO payment_allocation (payment_id, allocation_type,"
                + " membership_id, amount_cents) VALUES (:pay, 'MEMBERSHIP', :mid, :amt)")
                .bind("pay", paymentId).bind("mid", membershipId).bind("amt", amountCents).execute();
    }

    // ---- reads --------------------------------------------------------------

    /** Payments matching the given filters (any subset), newest first, allocations nested. */
    Page list(Long membershipId, Long householdId, Long periodId, int limit, int offset) {
        List<String> conds = new ArrayList<>();
        if (membershipId != null) conds.add("pa.membership_id = :membershipId");
        if (householdId != null) conds.add("m.household_id = :householdId");
        if (periodId != null) conds.add("m.membership_period_id = :periodId");
        boolean needMembershipJoin = householdId != null || periodId != null;
        String where = conds.isEmpty() ? "" :
                " WHERE EXISTS (SELECT 1 FROM payment_allocation pa"
                + (needMembershipJoin ? " JOIN membership m ON m.membership_id = pa.membership_id" : "")
                + " WHERE pa.payment_id = p.payment_id AND " + String.join(" AND ", conds) + ")";
        return jdbi.withHandle(handle -> {
            var count = handle.createQuery("SELECT count(*) FROM payment p" + where);
            var page = handle.createQuery("SELECT p.payment_id, p.received_date, p.amount_cents,"
                    + " p.payment_method, p.payer_person_id, p.bank_reference, p.recorded_by,"
                    + " p.recorded_at, p.notes FROM payment p" + where
                    + " ORDER BY p.received_date DESC, p.payment_id DESC LIMIT :limit OFFSET :offset")
                    .bind("limit", limit).bind("offset", offset);
            bindFilters(count, membershipId, householdId, periodId);
            bindFilters(page, membershipId, householdId, periodId);
            int total = count.mapTo(Integer.class).one();
            List<Payment> payments = page.map((rs, ctx) -> new Payment(
                    rs.getLong("payment_id"),
                    rs.getDate("received_date").toLocalDate(),
                    rs.getInt("amount_cents"), rs.getString("payment_method"),
                    (Long) rs.getObject("payer_person_id"), rs.getString("bank_reference"),
                    rs.getString("recorded_by"),
                    rs.getTimestamp("recorded_at").toInstant().toString(),
                    rs.getString("notes"), new ArrayList<>())).list();
            return new Page(attachAllocations(handle, payments), total);
        });
    }

    /** Payments that touch a given membership (for its detail view and the Reverse button). */
    List<Payment> listForMembership(long membershipId) {
        return list(membershipId, null, null, Integer.MAX_VALUE, 0).payments();
    }

    /** One payment by id, allocations nested — the receipt renderer's source row (CR-012). */
    static Optional<Payment> find(Handle handle, long id) {
        Optional<Payment> base = handle.createQuery(
                "SELECT p.payment_id, p.received_date, p.amount_cents, p.payment_method,"
                + " p.payer_person_id, p.bank_reference, p.recorded_by, p.recorded_at, p.notes"
                + " FROM payment p WHERE p.payment_id = :id")
                .bind("id", id)
                .map((rs, ctx) -> new Payment(rs.getLong("payment_id"),
                        rs.getDate("received_date").toLocalDate(),
                        rs.getInt("amount_cents"), rs.getString("payment_method"),
                        (Long) rs.getObject("payer_person_id"), rs.getString("bank_reference"),
                        rs.getString("recorded_by"),
                        rs.getTimestamp("recorded_at").toInstant().toString(),
                        rs.getString("notes"), new ArrayList<>()))
                .findOne();
        return base.map(p -> attachAllocations(handle, List.of(p)).get(0));
    }

    private static void bindFilters(org.jdbi.v3.core.statement.Query q,
                                    Long membershipId, Long householdId, Long periodId) {
        if (membershipId != null) q.bind("membershipId", membershipId);
        if (householdId != null) q.bind("householdId", householdId);
        if (periodId != null) q.bind("periodId", periodId);
    }

    private static List<Payment> attachAllocations(Handle handle, List<Payment> payments) {
        if (payments.isEmpty()) return payments;
        List<Long> ids = payments.stream().map(Payment::id).toList();
        Map<Long, List<Allocation>> byPayment = new HashMap<>();
        handle.createQuery(
                "SELECT pa.payment_allocation_id, pa.payment_id, pa.allocation_type, pa.membership_id,"
                + " pa.amount_cents, h.household_name"
                + " FROM payment_allocation pa"
                + " LEFT JOIN membership m ON m.membership_id = pa.membership_id"
                + " LEFT JOIN household h ON h.household_id = m.household_id"
                + " WHERE pa.payment_id IN (<ids>) ORDER BY pa.payment_allocation_id")
                .bindList("ids", ids)
                .map((rs, ctx) -> Map.entry(rs.getLong("payment_id"), new Allocation(
                        rs.getLong("payment_allocation_id"), rs.getString("allocation_type"),
                        (Long) rs.getObject("membership_id"), rs.getString("household_name"),
                        rs.getInt("amount_cents"))))
                .forEach(e -> byPayment.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return payments.stream().map(p -> new Payment(p.id(), p.receivedDate(), p.amountCents(),
                p.method(), p.payerPersonId(), p.bankReference(), p.recordedBy(), p.recordedAt(),
                p.notes(), byPayment.getOrDefault(p.id(), List.of()))).toList();
    }
}
