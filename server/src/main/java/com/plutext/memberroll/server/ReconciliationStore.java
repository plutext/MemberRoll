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
import org.jdbi.v3.core.statement.Query;
import org.jdbi.v3.core.statement.Update;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Reconciliation export + mark (CR-015). Reads the CR-001 {@code payment} /
 * {@code payment_allocation} tables the treasurer books from, one row per
 * payment with its allocation split projected into MEMBERSHIP / JOURNAL /
 * DONATION / OTHER columns, and is the first (and only) writer of the
 * {@code reconciliation_status} column CR-001 provisioned in V1 and left
 * unused. That column is operational state — deliberately separated from the
 * financial facts — so flipping it to RECONCILED does not breach the
 * insert-only discipline the payment rows themselves keep (a correction is
 * still a negative payment, never an edit).
 *
 * <p>The same {@link Filter} drives export and mark, so the mark step operates
 * on exactly the window the export showed; the {@code maxPaymentId} bound the
 * export hands back means a payment recorded between export and mark is never
 * swept in unseen.
 */
final class ReconciliationStore {

    /** Canonical order for the per-method summary breakout (only present methods emitted). */
    static final List<String> METHOD_ORDER = List.of("CASH", "CHEQUE", "BANK_TRANSFER", "STRIPE", "OTHER");

    /** The export/mark window. A null field means "no bound on this dimension". */
    record Filter(LocalDate from, LocalDate to, String method, boolean unreconciledOnly) {
        Filter withMethod(String m) {
            return new Filter(from, to, m, unreconciledOnly);
        }
    }

    /** One detail row: a payment with its allocation split summed into columns (split sums to gross). */
    record Row(long paymentId, LocalDate receivedDate, String method, String payer, String household,
               int grossCents, int membershipCents, int journalCents, int donationCents, int otherCents,
               String periods, String bankReference, String stripeTxnId, String status,
               String recordedBy, String notes) {}

    /** Net totals for the window: per allocation type, per method, and gross, plus the row count. */
    record Totals(long membershipCents, long journalCents, long donationCents, long otherCents,
                  long grossCents, int count, Map<String, Long> byMethod) {}

    /** The export payload: detail rows, totals, and the high-water payment id for the mark step. */
    record Export(List<Row> rows, Totals totals, Long maxPaymentId) {}

    private final Jdbi jdbi;

    ReconciliationStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    // ---- read ---------------------------------------------------------------

    Export export(Filter f) {
        return jdbi.withHandle(handle -> {
            List<String> conds = new ArrayList<>();
            if (f.from() != null) conds.add("p.received_date >= :from");
            if (f.to() != null) conds.add("p.received_date <= :to");
            if (f.method() != null) conds.add("p.payment_method = :method");
            if (f.unreconciledOnly()) conds.add("p.reconciliation_status = 'UNRECONCILED'");
            String where = conds.isEmpty() ? "" : " WHERE " + String.join(" AND ", conds);

            Query q = handle.createQuery(
                    "SELECT p.payment_id, p.received_date, p.amount_cents, p.payment_method,"
                    + " p.bank_reference, p.external_transaction_id, p.reconciliation_status,"
                    + " p.recorded_by, p.notes, pe.given_name, pe.family_name"
                    + " FROM payment p LEFT JOIN person pe ON pe.person_id = p.payer_person_id"
                    + where + " ORDER BY p.received_date, p.payment_id");
            bindFilter(q, f);

            // Materialise the base rows fully before the allocation query runs on
            // the same handle, then fold allocations into them. Keyed by id, kept
            // in query (date) order.
            LinkedHashMap<Long, Acc> accs = new LinkedHashMap<>();
            for (Acc a : q.map((rs, ctx) -> {
                Acc acc = new Acc();
                acc.paymentId = rs.getLong("payment_id");
                acc.receivedDate = rs.getDate("received_date").toLocalDate();
                acc.method = rs.getString("payment_method");
                acc.grossCents = rs.getInt("amount_cents");
                acc.bankReference = rs.getString("bank_reference");
                acc.stripeTxnId = rs.getString("external_transaction_id");
                acc.status = rs.getString("reconciliation_status");
                acc.recordedBy = rs.getString("recorded_by");
                acc.notes = rs.getString("notes");
                acc.payer = fullName(rs.getString("given_name"), rs.getString("family_name"));
                return acc;
            }).list()) {
                accs.put(a.paymentId, a);
            }

            if (!accs.isEmpty()) attachAllocations(handle, accs);

            List<Row> rows = new ArrayList<>();
            long mship = 0, journal = 0, donation = 0, other = 0, gross = 0;
            Map<String, Long> byMethod = new LinkedHashMap<>();
            for (Acc a : accs.values()) {
                rows.add(a.toRow());
                mship += a.membershipCents;
                journal += a.journalCents;
                donation += a.donationCents;
                other += a.otherCents;
                gross += a.grossCents;
                byMethod.merge(a.method, (long) a.grossCents, Long::sum);
            }
            // re-key byMethod into canonical order for a stable summary block
            Map<String, Long> ordered = new LinkedHashMap<>();
            for (String m : METHOD_ORDER) if (byMethod.containsKey(m)) ordered.put(m, byMethod.get(m));
            byMethod.forEach(ordered::putIfAbsent); // any non-canonical method, defensively

            Long maxPaymentId = accs.isEmpty() ? null
                    : accs.keySet().stream().max(Long::compare).orElse(null);
            Totals totals = new Totals(mship, journal, donation, other, gross, rows.size(), ordered);
            return new Export(rows, totals, maxPaymentId);
        });
    }

    private record AllocRow(long paymentId, String type, int amountCents, String householdName,
                            String periodName) {}

    private static void attachAllocations(Handle handle, LinkedHashMap<Long, Acc> accs) {
        List<AllocRow> rows = handle.createQuery(
                "SELECT pa.payment_id, pa.allocation_type, pa.amount_cents,"
                + " h.household_name, per.name AS period_name"
                + " FROM payment_allocation pa"
                + " LEFT JOIN membership m ON m.membership_id = pa.membership_id"
                + " LEFT JOIN household h ON h.household_id = m.household_id"
                + " LEFT JOIN membership_period per ON per.membership_period_id = m.membership_period_id"
                + " WHERE pa.payment_id IN (<ids>) ORDER BY pa.payment_allocation_id")
                .bindList("ids", new ArrayList<>(accs.keySet()))
                .map((rs, ctx) -> new AllocRow(rs.getLong("payment_id"), rs.getString("allocation_type"),
                        rs.getInt("amount_cents"), rs.getString("household_name"),
                        rs.getString("period_name")))
                .list();
        for (AllocRow r : rows) {
            Acc a = accs.get(r.paymentId());
            if (a == null) continue;
            switch (r.type()) {
                case "MEMBERSHIP" -> a.membershipCents += r.amountCents();
                case "JOURNAL" -> a.journalCents += r.amountCents();
                case "DONATION" -> a.donationCents += r.amountCents();
                default -> a.otherCents += r.amountCents();
            }
            if (r.householdName() != null) a.households.add(r.householdName());
            // Period is the MEMBERSHIP allocations' period(s); blank for pure donations
            if ("MEMBERSHIP".equals(r.type()) && r.periodName() != null) a.periods.add(r.periodName());
        }
    }

    // ---- write --------------------------------------------------------------

    /**
     * Flip UNRECONCILED payments matching {@code f}'s from/to/method with
     * {@code payment_id <= maxPaymentId} to RECONCILED, in the caller's
     * transaction. Idempotent — a second call marks 0. {@code unreconciledOnly}
     * on the filter is ignored here: the mark is UNRECONCILED-bound by
     * definition. Returns the number of rows flipped.
     */
    int reconcile(Handle handle, Filter f, long maxPaymentId) {
        List<String> conds = new ArrayList<>();
        conds.add("reconciliation_status = 'UNRECONCILED'");
        conds.add("payment_id <= :max");
        if (f.from() != null) conds.add("received_date >= :from");
        if (f.to() != null) conds.add("received_date <= :to");
        if (f.method() != null) conds.add("payment_method = :method");
        Update u = handle.createUpdate("UPDATE payment SET reconciliation_status = 'RECONCILED'"
                + " WHERE " + String.join(" AND ", conds)).bind("max", maxPaymentId);
        if (f.from() != null) u.bind("from", f.from());
        if (f.to() != null) u.bind("to", f.to());
        if (f.method() != null) u.bind("method", f.method());
        return u.execute();
    }

    // ---- helpers ------------------------------------------------------------

    private static void bindFilter(Query q, Filter f) {
        if (f.from() != null) q.bind("from", f.from());
        if (f.to() != null) q.bind("to", f.to());
        if (f.method() != null) q.bind("method", f.method());
    }

    private static String fullName(String given, String family) {
        String g = given == null ? "" : given.trim();
        String fam = family == null ? "" : family.trim();
        return (g + " " + fam).trim();
    }

    /** Mutable per-payment accumulator; allocations fold into the type columns and the name sets. */
    private static final class Acc {
        long paymentId;
        LocalDate receivedDate;
        String method;
        int grossCents;
        int membershipCents;
        int journalCents;
        int donationCents;
        int otherCents;
        String payer;
        String bankReference;
        String stripeTxnId;
        String status;
        String recordedBy;
        String notes;
        final LinkedHashSet<String> households = new LinkedHashSet<>();
        final LinkedHashSet<String> periods = new LinkedHashSet<>();

        Row toRow() {
            return new Row(paymentId, receivedDate, method, payer, String.join("; ", households),
                    grossCents, membershipCents, journalCents, donationCents, otherCents,
                    String.join("; ", periods), bankReference, stripeTxnId, status, recordedBy, notes);
        }
    }
}
