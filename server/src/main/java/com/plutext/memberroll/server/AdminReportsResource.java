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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * CR-019: the four cross-cutting report exports, admin-only, in the existing
 * CSV idiom (Commons CSV, Content-Disposition attachment) — deliberately no
 * report builder, no stored reports, no PDF. The period-scoped exports (AGM,
 * mailing labels, financial) stay on AdminPeriodsResource and the
 * payment-scoped ones (reconciliation, Xero journal) on AdminPaymentsResource;
 * this resource holds only reports that are not scoped to one period. A bad
 * parameter is a 400 with a JSON error, never an empty CSV.
 */
@Path("admin/export")
@RolesAllowed("admin")
public class AdminReportsResource {

    private final Jdbi jdbi = Db.jdbi();
    private final ReportStore reports = new ReportStore(jdbi);
    private final PeriodStore periods = new PeriodStore(jdbi);

    /** Report A — the clause 4 register of members (CR-011 Stage 1). */
    @GET
    @Path("register-of-members.csv")
    @Produces("text/csv")
    public Response registerOfMembers() {
        StringWriter sw = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            csv.printRecord("Family name", "Given name", "Address (postal or email)",
                    "Date became a member", "Date ceased");
            for (ReportStore.RegisterRow r : reports.registerOfMembers()) {
                csv.printRecord(r.familyName(), r.givenName(), blank(r.address()),
                        str(r.becameMember()), str(r.ceased()));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return csvResponse(sw.toString(), "register-of-members.csv");
    }

    /** Report B — current household people with no membership place in the period. */
    @GET
    @Path("no-current-membership.csv")
    @Produces("text/csv")
    public Response noCurrentMembership(@QueryParam("periodId") Long periodId) {
        if (periodId == null) return badRequest("periodId is required");
        if (periods.get(periodId).isEmpty()) return badRequest("no membership period #" + periodId);
        StringWriter sw = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            csv.printRecord("Family name", "Given name", "Household", "Relationship",
                    "Email", "Phone", "Last period held");
            for (ReportStore.NoMembershipRow r : reports.noCurrentMembership(periodId)) {
                csv.printRecord(r.familyName(), r.givenName(), r.household(), r.relationship(),
                        blank(r.email()), blank(r.phone()), blank(r.lastPeriod()));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return csvResponse(sw.toString(), "no-current-membership.csv");
    }

    /** Report C — households ACTIVE in the from-period, anything else in the to-period. */
    @GET
    @Path("unrenewed.csv")
    @Produces("text/csv")
    public Response unrenewed(@QueryParam("fromPeriodId") Long fromPeriodId,
                              @QueryParam("toPeriodId") Long toPeriodId) {
        if (fromPeriodId == null || toPeriodId == null) {
            return badRequest("fromPeriodId and toPeriodId are required");
        }
        PeriodStore.Period from = periods.get(fromPeriodId).orElse(null);
        PeriodStore.Period to = periods.get(toPeriodId).orElse(null);
        if (from == null) return badRequest("no membership period #" + fromPeriodId);
        if (to == null) return badRequest("no membership period #" + toPeriodId);
        if (!to.startDate().isAfter(from.startDate())) {
            return badRequest("the to-period must start after the from-period");
        }
        StringWriter sw = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            csv.printRecord("Household", "Primary contact", "Email", "Phone",
                    "Type in " + from.name(), "Status in " + to.name());
            for (ReportStore.UnrenewedRow r : reports.unrenewed(fromPeriodId, toPeriodId)) {
                csv.printRecord(r.household(), r.primaryContact(), blank(r.email()),
                        blank(r.phone()), r.fromType(),
                        r.toStatus() == null ? "—" : r.toStatus());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return csvResponse(sw.toString(), "unrenewed.csv");
    }

    /** Report D — payments carrying a DONATION allocation, with a trailing total. */
    @GET
    @Path("donations.csv")
    @Produces("text/csv")
    public Response donations(@QueryParam("from") String fromParam, @QueryParam("to") String toParam) {
        LocalDate from;
        LocalDate to;
        try {
            from = fromParam == null || fromParam.isBlank() ? null : LocalDate.parse(fromParam);
            to = toParam == null || toParam.isBlank() ? null : LocalDate.parse(toParam);
        } catch (DateTimeParseException e) {
            return badRequest("dates must be YYYY-MM-DD");
        }
        if (from != null && to != null && from.isAfter(to)) {
            return badRequest("'from' must not be after 'to'");
        }
        List<ReportStore.DonationRow> rows = reports.donations(from, to);
        StringWriter sw = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
            csv.printRecord("Date", "Payment id", "Payer", "Method", "Donation",
                    "Payment total", "External transaction id");
            long total = 0;
            for (ReportStore.DonationRow r : rows) {
                total += r.donationCents();
                csv.printRecord(r.receivedDate(), r.paymentId(), blank(r.payer()), r.method(),
                        dollars(r.donationCents()), dollars(r.paymentTotalCents()),
                        blank(r.externalTransactionId()));
            }
            // the CR-015 labelled-summary pattern: a blank line, then the total
            csv.println();
            csv.printRecord("Total donations", "", "", "", dollars(total), "", "");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return csvResponse(sw.toString(), "donations.csv");
    }

    // ---- helpers ------------------------------------------------------------

    private static String dollars(long cents) {
        return String.format("%s%d.%02d", cents < 0 ? "-" : "", Math.abs(cents) / 100,
                Math.abs(cents) % 100);
    }

    private static String blank(String s) {
        return s == null ? "" : s;
    }

    private static String str(LocalDate d) {
        return d == null ? "" : d.toString();
    }

    private static Response csvResponse(String csv, String filename) {
        return Response.ok(csv).type("text/csv")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"").build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Json.createObjectBuilder().add("error", message).build().toString())
                .type("application/json").build();
    }
}
