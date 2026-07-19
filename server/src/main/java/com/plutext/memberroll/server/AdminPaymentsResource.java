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

import jakarta.annotation.security.RolesAllowed;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Manual payment entry (CR-003), admin-only. Payments are insert-only: a
 * mistake is corrected by an equal-and-opposite (negative) payment, never an
 * edit or delete — hence no PUT/DELETE here. Recording a payment recomputes
 * every membership it touches in the same transaction, so ACTIVE always
 * follows from allocations covering the fee (schema rule 6). Positive STRIPE
 * payments only ever arrive via CR-004's webhook; the one hand-entered STRIPE
 * shape is a NEGATIVE amount — recording a refund made in the Stripe
 * dashboard (refunds move money there, never here).
 */
@Path("admin/payments")
@RolesAllowed("admin")
public class AdminPaymentsResource {

    private static final Set<String> METHODS = Set.of("CASH", "CHEQUE", "BANK_TRANSFER", "STRIPE", "OTHER");
    private static final Set<String> ALLOCATION_TYPES = Set.of("MEMBERSHIP", "JOURNAL", "DONATION", "OTHER");

    private final Jdbi jdbi = Db.jdbi();
    private final PaymentStore payments = new PaymentStore(jdbi);
    private final MembershipStore memberships = new MembershipStore(jdbi);
    private final ReconciliationStore reconciliation = new ReconciliationStore(jdbi);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(String body, @Context SecurityContext security) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        String recordedBy = security.getUserPrincipal() instanceof UserPrincipal user
                ? user.getUsername() : "admin";
        try {
            LocalDate receivedDate = Payloads.reqDate(request, "receivedDate");
            int amountCents = Payloads.reqInt(request, "amountCents");
            if (amountCents == 0) return badRequest("amountCents must be non-zero");
            String method = Payloads.optString(request, "method");
            if (method == null || !METHODS.contains(method)) {
                return badRequest("method must be one of " + METHODS);
            }
            Long payerPersonId = Payloads.optLong(request, "payerPersonId");
            String bankReference = Payloads.optString(request, "bankReference");
            String notes = Payloads.optString(request, "notes");
            List<PaymentStore.AllocationInput> allocations = parseAllocations(request);
            // every LINE must be negative, not just the total — a negative-total
            // STRIPE entry must not smuggle in a positive MEMBERSHIP allocation
            // that never came through the webhook
            if ("STRIPE".equals(method)
                    && (amountCents > 0 || allocations.stream().anyMatch(a -> a.amountCents() > 0))) {
                return badRequest("positive STRIPE amounts arrive via the webhook only;"
                        + " hand-entered STRIPE must be all-negative (recording a dashboard refund)");
            }

            JsonObject result = jdbi.inTransaction(handle -> {
                // membership FKs must resolve to a clean 400, not a raw SQL error
                for (PaymentStore.AllocationInput a : allocations) {
                    if (a.membershipId() != null && !memberships.exists(handle, a.membershipId())) {
                        throw new IllegalArgumentException("no such membership #" + a.membershipId());
                    }
                }
                PaymentStore.InsertResult inserted = payments.insert(handle, receivedDate, amountCents,
                        method, payerPersonId, bankReference, null, notes, recordedBy, allocations);
                for (long membershipId : inserted.touchedMembershipIds()) {
                    memberships.recompute(handle, membershipId, receivedDate);
                }
                // read the fresh state (uncommitted, same handle) for the response
                JsonArrayBuilder touched = Json.createArrayBuilder();
                JsonArrayBuilder warnings = Json.createArrayBuilder();
                for (long membershipId : inserted.touchedMembershipIds()) {
                    MembershipStore.Detail d = MembershipStore.get(handle, membershipId).orElseThrow();
                    touched.add(Json.createObjectBuilder().add("id", d.id()).add("status", d.status())
                            .add("amountDueCents", d.amountDueCents())
                            .add("amountPaidCents", d.amountPaidCents()));
                    if (d.amountPaidCents() > d.amountDueCents()) {
                        warnings.add("membership #" + d.id() + " is overpaid (paid "
                                + d.amountPaidCents() + " > due " + d.amountDueCents() + ")");
                    }
                }
                return Json.createObjectBuilder().add("id", inserted.paymentId())
                        .add("memberships", touched).add("warnings", warnings).build();
            });
            return Response.status(Response.Status.CREATED).entity(result.toString()).build();
        } catch (ConflictException e) {
            return conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("membershipId") Long membershipId,
                         @QueryParam("householdId") Long householdId,
                         @QueryParam("periodId") Long periodId,
                         @QueryParam("limit") Integer limit,
                         @QueryParam("offset") Integer offset) {
        int lim = Math.max(1, Math.min(limit == null ? 50 : limit, 500));
        int off = Math.max(0, offset == null ? 0 : offset);
        PaymentStore.Page page = payments.list(membershipId, householdId, periodId, lim, off);
        JsonArrayBuilder rows = Json.createArrayBuilder();
        for (PaymentStore.Payment p : page.payments()) rows.add(paymentJson(p));
        return Response.ok(Json.createObjectBuilder()
                .add("payments", rows).add("total", page.total()).build().toString()).build();
    }

    // ---- receipts (CR-012) --------------------------------------------------

    /**
     * The composed receipt as JSON (header/line/total fields, the canonical
     * {@code text}, and {@code defaultTo}). Renders from the recorded payment,
     * so it is the same document the email and print paths produce. 404 for an
     * unknown payment. Stateless — nothing is written, re-fetch re-composes.
     */
    @GET
    @Path("{id}/receipt")
    @Produces(MediaType.APPLICATION_JSON)
    public Response receipt(@PathParam("id") long id) {
        return jdbi.withHandle(handle -> {
            PaymentStore.Payment p = PaymentStore.find(handle, id).orElse(null);
            if (p == null) return notFound("no such payment #" + id);
            Receipts.Receipt receipt = Receipts.render(handle, p);
            String defaultTo = Receipts.defaultRecipient(handle, p).orElse(null);
            return Response.ok(Receipts.toJson(receipt, defaultTo).toString()).build();
        });
    }

    /**
     * Email the receipt. Body {@code {to?}}; absent {@code to} uses the default
     * address, or 400 when there is none. 503 when mail is not configured (an
     * explicit refusal mirroring checkout's, never a silent no-op). 404 for an
     * unknown payment. Stateless — re-sending re-composes and writes nothing.
     */
    @POST
    @Path("{id}/receipt")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response emailReceipt(@PathParam("id") long id, String body) {
        JsonObject request = body == null || body.isBlank()
                ? JsonValue.EMPTY_JSON_OBJECT : Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        String to = Payloads.optString(request, "to");
        return jdbi.withHandle(handle -> {
            PaymentStore.Payment p = PaymentStore.find(handle, id).orElse(null);
            if (p == null) return notFound("no such payment #" + id);
            if (!Mail.enabled()) {
                return serviceUnavailable("email is not configured on this server"
                        + " — print the receipt or configure SMTP");
            }
            Receipts.Receipt receipt = Receipts.render(handle, p);
            String recipient = to != null ? to : Receipts.defaultRecipient(handle, p).orElse(null);
            if (recipient == null) {
                return badRequest("no email on file for this payment — supply \"to\"");
            }
            // async: SMTP must never hold a Tomcat thread past the response
            Mail.sendAsync(recipient, Receipts.subject(receipt), receipt.text());
            return Response.accepted(Json.createObjectBuilder()
                    .add("sentTo", recipient).build().toString()).build();
        });
    }

    // ---- reconciliation export (CR-015) -------------------------------------

    /**
     * The categorised detail export the treasurer books from: one row per
     * payment with its allocation split (MEMBERSHIP/JOURNAL/DONATION/OTHER)
     * projected into columns that sum to Gross, then a labelled trailing
     * summary block (net totals by type and by method, and the payment count).
     * All filters optional; amounts are signed dollars (a refund row is
     * negative).
     */
    @GET
    @Path("export/reconciliation.csv")
    @Produces("text/csv")
    public Response exportReconciliationCsv(@QueryParam("from") String from, @QueryParam("to") String to,
            @QueryParam("method") String method, @QueryParam("unreconciledOnly") boolean unreconciledOnly) {
        ReconciliationStore.Filter filter;
        try {
            filter = parseFilter(from, to, method, unreconciledOnly);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        ReconciliationStore.Export export = reconciliation.export(filter);
        StringWriter sw = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            csv.printRecord("Payment id", "Received date", "Method", "Payer", "Household", "Gross",
                    "Membership", "Journal", "Donation", "Other", "Period", "Bank reference",
                    "Stripe txn id", "Status", "Recorded by", "Notes");
            for (ReconciliationStore.Row r : export.rows()) {
                csv.printRecord(r.paymentId(), r.receivedDate(), r.method(), r.payer(), r.household(),
                        money(r.grossCents()), money(r.membershipCents()), money(r.journalCents()),
                        money(r.donationCents()), money(r.otherCents()), r.periods(),
                        nullToBlank(r.bankReference()), nullToBlank(r.stripeTxnId()), r.status(),
                        r.recordedBy(), nullToBlank(r.notes()));
            }
            // trailing summary block — the numbers the treasurer types into Xero.
            // The totals row aligns under the detail's amount columns (Gross..Other);
            // per-method nets align under Gross.
            ReconciliationStore.Totals t = export.totals();
            csv.println();
            csv.printRecord("TOTAL (net)", "", "", "", "", money(t.grossCents()),
                    money(t.membershipCents()), money(t.journalCents()),
                    money(t.donationCents()), money(t.otherCents()));
            csv.printRecord("Payments (count)", t.count());
            for (Map.Entry<String, Long> e : t.byMethod().entrySet()) {
                csv.printRecord(e.getKey(), "", "", "", "", money(e.getValue()));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return csvResponse(sw.toString(), "reconciliation.csv");
    }

    /** The same window as the CSV, as JSON — the UI's pre-download preview; carries maxPaymentId for the mark step. */
    @GET
    @Path("export/reconciliation")
    @Produces(MediaType.APPLICATION_JSON)
    public Response exportReconciliationJson(@QueryParam("from") String from, @QueryParam("to") String to,
            @QueryParam("method") String method, @QueryParam("unreconciledOnly") boolean unreconciledOnly) {
        ReconciliationStore.Filter filter;
        try {
            filter = parseFilter(from, to, method, unreconciledOnly);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        ReconciliationStore.Export export = reconciliation.export(filter);
        ReconciliationStore.Totals t = export.totals();
        JsonObject byType = Json.createObjectBuilder()
                .add("MEMBERSHIP", t.membershipCents()).add("JOURNAL", t.journalCents())
                .add("DONATION", t.donationCents()).add("OTHER", t.otherCents()).build();
        JsonObjectBuilder byMethod = Json.createObjectBuilder();
        t.byMethod().forEach(byMethod::add);
        JsonObject totals = Json.createObjectBuilder()
                .add("byType", byType).add("byMethod", byMethod)
                .add("gross", t.grossCents()).build();
        JsonObjectBuilder out = Json.createObjectBuilder().add("count", t.count()).add("totals", totals);
        if (export.maxPaymentId() == null) out.addNull("maxPaymentId");
        else out.add("maxPaymentId", export.maxPaymentId());
        return Response.ok(out.build().toString()).build();
    }

    /**
     * The importable Xero manual journal for the window's STRIPE payments (§3):
     * debit the clearing account for the gross Stripe total, credit each income
     * account its net type total. One shared Narration+Date groups the lines
     * into one journal on import; a positive Amount is a debit, negative a
     * credit (so a refund-heavy window flips a line's sign naturally). Forces
     * {@code method=STRIPE} — the clearing pattern is a payout pattern. 409
     * until the account mapping is saved (never a guessed code).
     */
    @GET
    @Path("export/xero-journal.csv")
    @Produces("text/csv")
    public Response exportXeroJournal(@QueryParam("from") String from, @QueryParam("to") String to,
            @QueryParam("unreconciledOnly") boolean unreconciledOnly) {
        ReconciliationStore.Filter filter;
        try {
            filter = parseFilter(from, to, "STRIPE", unreconciledOnly);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        Optional<XeroAccounts.Mapping> mapping = jdbi.withHandle(XeroAccounts::read);
        if (mapping.isEmpty()) {
            return conflict("save the Xero account mapping before exporting a journal");
        }
        XeroAccounts.Mapping m = mapping.get();
        ReconciliationStore.Export export = reconciliation.export(filter);
        ReconciliationStore.Totals t = export.totals();
        // journal date: the window's last received date, else the 'to' bound, else today
        LocalDate date = export.rows().isEmpty()
                ? (filter.to() != null ? filter.to() : LocalDate.now())
                : export.rows().get(export.rows().size() - 1).receivedDate();
        String narration = "MemberRoll Stripe reconciliation" + windowLabel(filter);
        StringWriter sw = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            csv.printRecord("Narration", "Date", "AccountCode", "TaxRate", "Amount");
            // clearing debit (+gross); income credits (-type net). Zero lines skipped;
            // the balance holds because gross = sum of the type totals.
            journalLine(csv, narration, date, m.clearingCode(), m.taxRate(), t.grossCents());
            journalLine(csv, narration, date, m.membershipCode(), m.taxRate(), -t.membershipCents());
            journalLine(csv, narration, date, m.journalCode(), m.taxRate(), -t.journalCents());
            journalLine(csv, narration, date, m.donationCode(), m.taxRate(), -t.donationCents());
            journalLine(csv, narration, date, m.otherCode(), m.taxRate(), -t.otherCents());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return csvResponse(sw.toString(), "xero-journal.csv");
    }

    /** GET/PUT the Xero account mapping (CR-014's app_setting-blob pattern; codes are opaque). */
    @GET
    @Path("xero-account-mapping")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getXeroMapping() {
        Optional<XeroAccounts.Mapping> m = jdbi.withHandle(XeroAccounts::read);
        JsonObjectBuilder b = Json.createObjectBuilder().add("configured", m.isPresent());
        m.ifPresent(mm -> b.add("membershipCode", mm.membershipCode())
                .add("journalCode", mm.journalCode()).add("donationCode", mm.donationCode())
                .add("otherCode", mm.otherCode()).add("clearingCode", mm.clearingCode())
                .add("taxRate", mm.taxRate()));
        return Response.ok(b.build().toString()).build();
    }

    @PUT
    @Path("xero-account-mapping")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response putXeroMapping(String body, @Context SecurityContext security) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        String membership = Payloads.optString(request, "membershipCode");
        String journal = Payloads.optString(request, "journalCode");
        String donation = Payloads.optString(request, "donationCode");
        String other = Payloads.optString(request, "otherCode");
        String clearing = Payloads.optString(request, "clearingCode");
        for (Map.Entry<String, String> e : Map.of("membershipCode", nullToBlank(membership),
                "journalCode", nullToBlank(journal), "donationCode", nullToBlank(donation),
                "otherCode", nullToBlank(other), "clearingCode", nullToBlank(clearing)).entrySet()) {
            if (e.getValue().isEmpty()) return badRequest(e.getKey() + " is required");
        }
        String taxRate = Payloads.optString(request, "taxRate");
        if (taxRate == null) taxRate = XeroAccounts.DEFAULT_TAX_RATE;
        String whom = security.getUserPrincipal() instanceof UserPrincipal user ? user.getUsername() : "admin";
        String json = Json.createObjectBuilder()
                .add("membershipCode", membership).add("journalCode", journal)
                .add("donationCode", donation).add("otherCode", other)
                .add("clearingCode", clearing).add("taxRate", taxRate).build().toString();
        jdbi.useHandle(h -> h.createUpdate(
                "INSERT INTO app_setting (key, value, updated_by) VALUES (:k, :v, :by)"
                        + " ON CONFLICT (key) DO UPDATE SET value = :v, updated_by = :by, updated_at = now()")
                .bind("k", XeroAccounts.SETTINGS_KEY).bind("v", json).bind("by", whom).execute());
        return getXeroMapping();
    }

    /**
     * Mark the window's UNRECONCILED payments up to {@code maxPaymentId}
     * RECONCILED. {@code maxPaymentId} (what the export handed back) is
     * required, so a payment recorded between export and mark is never swept in
     * unseen. Idempotent — a re-post marks 0.
     */
    @POST
    @Path("reconcile")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response reconcile(String body) {
        JsonObject request = Payloads.read(body);
        if (request == null) return badRequest("body must be a JSON object");
        Long maxPaymentId;
        try {
            maxPaymentId = Payloads.optLong(request, "maxPaymentId");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        if (maxPaymentId == null) return badRequest("maxPaymentId is required");
        ReconciliationStore.Filter filter;
        try {
            filter = parseFilter(Payloads.optString(request, "from"), Payloads.optString(request, "to"),
                    Payloads.optString(request, "method"), false);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        long max = maxPaymentId;
        int marked = jdbi.inTransaction(handle -> reconciliation.reconcile(handle, filter, max));
        return Response.ok(Json.createObjectBuilder().add("marked", marked).build().toString()).build();
    }

    // ---- helpers ------------------------------------------------------------

    private static ReconciliationStore.Filter parseFilter(String from, String to, String method,
                                                          boolean unreconciledOnly) {
        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");
        String m = method == null || method.isBlank() ? null : method;
        if (m != null && !METHODS.contains(m)) throw new IllegalArgumentException("method must be one of " + METHODS);
        return new ReconciliationStore.Filter(fromDate, toDate, m, unreconciledOnly);
    }

    private static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " must be an ISO date (YYYY-MM-DD)");
        }
    }

    private static void journalLine(CSVPrinter csv, String narration, LocalDate date, String code,
                                    String taxRate, long amountCents) throws IOException {
        if (amountCents == 0) return; // no zero-amount journal lines; the balance still holds
        csv.printRecord(narration, date, code, taxRate, money(amountCents));
    }

    private static String windowLabel(ReconciliationStore.Filter f) {
        if (f.from() == null && f.to() == null) return "";
        return " " + (f.from() == null ? "…" : f.from()) + ".." + (f.to() == null ? "…" : f.to());
    }

    /** Signed dollars for a CSV cell (e.g. 60.00, -45.00) — plain, spreadsheet-friendly, no currency symbol. */
    private static String money(long cents) {
        long abs = Math.abs(cents);
        return (cents < 0 ? "-" : "") + (abs / 100) + "."
                + String.format(java.util.Locale.ROOT, "%02d", abs % 100);
    }

    private static String nullToBlank(String s) {
        return s == null ? "" : s;
    }

    private static Response csvResponse(String csv, String filename) {
        return Response.ok(csv).type("text/csv")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"").build();
    }

    private List<PaymentStore.AllocationInput> parseAllocations(JsonObject request) {
        JsonArray raw = request.containsKey("allocations") && !request.isNull("allocations")
                ? request.getJsonArray("allocations") : null;
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("at least one allocation is required");
        }
        List<PaymentStore.AllocationInput> allocations = new ArrayList<>();
        for (JsonValue v : raw) {
            JsonObject a = v.asJsonObject();
            String type = Payloads.optString(a, "type");
            if (type == null || !ALLOCATION_TYPES.contains(type)) {
                throw new IllegalArgumentException("allocation type must be one of " + ALLOCATION_TYPES);
            }
            allocations.add(new PaymentStore.AllocationInput(type,
                    Payloads.optLong(a, "membershipId"), Payloads.reqInt(a, "amountCents")));
        }
        return allocations;
    }

    static JsonObject paymentJson(PaymentStore.Payment p) {
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("id", p.id())
                .add("receivedDate", p.receivedDate().toString())
                .add("amountCents", p.amountCents())
                .add("method", p.method());
        if (p.payerPersonId() == null) b.addNull("payerPersonId"); else b.add("payerPersonId", p.payerPersonId());
        AdminPeopleResource.addNullable(b, "bankReference", p.bankReference());
        b.add("reconciliationStatus", p.reconciliationStatus());
        b.add("recordedBy", p.recordedBy()).add("recordedAt", p.recordedAt());
        AdminPeopleResource.addNullable(b, "notes", p.notes());
        JsonArrayBuilder allocations = Json.createArrayBuilder();
        for (PaymentStore.Allocation a : p.allocations()) {
            JsonObjectBuilder ab = Json.createObjectBuilder().add("id", a.id()).add("type", a.type());
            if (a.membershipId() == null) ab.addNull("membershipId"); else ab.add("membershipId", a.membershipId());
            AdminPeopleResource.addNullable(ab, "householdName", a.householdName());
            allocations.add(ab.add("amountCents", a.amountCents()));
        }
        return b.add("allocations", allocations).build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Json.createObjectBuilder().add("error", message).build().toString()).build();
    }

    private static Response conflict(String message) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Json.createObjectBuilder().add("error", message).build().toString()).build();
    }

    private static Response notFound(String message) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Json.createObjectBuilder().add("error", message).build().toString()).build();
    }

    private static Response serviceUnavailable(String message) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Json.createObjectBuilder().add("error", message).build().toString()).build();
    }
}
