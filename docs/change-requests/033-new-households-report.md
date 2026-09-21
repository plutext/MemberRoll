# CR-033 — "New households" report

## Problem

"Is there a way to get a list of households who have recently joined?"
(2026-09-21). There isn't: nothing in the panel sorts or filters by
joining date. The nearest things are the CR-019 register of members
(per person, "Date became a member" — sort it in a spreadsheet), the
CR-007 applications queue (only form-based joiners, not the wizard),
and psql on the box.

## Objective

A fifth CSV on the Reports page — **New households** — one row per
household whose joining date falls in a received-style date window
(default: the last 90 days), with the contact details a welcome letter
or a committee report needs.

## Design

### 1. "Joined" = the household's earliest `membership.start_date`

The same derivation the CR-019 register uses for "date became a
member", lifted from person to household: `MIN(m.start_date)` over
ALL the household's memberships, any status (CEASED included — joined
is joined). It is the right date because `MembershipStore.
createForHousehold` stamps a mid-period creation with **today**
(wizard, CR-007 approval) and only a rollover/period-start creation
with the period's start; so a household that joined on 14 Aug 2026 is
dated 2026-08-14 while its 2026-27 rollover row (2026-09-01) never
wins the MIN. The CR-019 limitation carries over verbatim: an imported
household is dated the start of its earliest imported year (the
import has no true join date) — and that is exactly why the default
90-day window is useful: imported households fall outside it by
construction once the import year is more than 90 days old.

Rejected: `application_date`/`approved_date` (only CR-007 sets them —
wizard households would vanish), `household_id` order (a number, not
a date, and the CR-020 lesson: ids are allocation order forever),
a new `household.created_at` column (a migration to record what
`MIN(start_date)` already says).

### 2. Endpoint

`GET /api/admin/export/new-households.csv?from=&to=` on
`AdminReportsResource` (`{"admin","manager"}` like every report —
it's a register read). `from`/`to` are optional ISO dates, open ends
when blank, 400 on a bad date or `from > to` (the donations
validation, verbatim). No default window server-side (all-time when
both blank — the donations contract), the UI supplies the 90-day
default. Columns:

```
Household,Primary contact,Email,Phone,Joined,Type,Period,Status
```

Email/phone = the primary contact's primary email/phone (the
unrenewed report's LATERAL pick). Type/Period/Status describe the
**joining membership** (the one carrying the MIN start; ties broken
by lowest membership id), so the row says what they signed up for,
whether it's paid, and which year it was — the questions a welcome or
a chase asks. Rows sort newest joined first, then household name.

`ReportStore.newHouseholds(from, to)` is the read (hand-written SQL,
one record per row, nothing written — the CR-019 pattern).

### 3. UI

A "New households" block on `admin/reports.html` between Unrenewed
and Donations: two date inputs, From prefilled to today − 90 days by
`wireReports` (To blank = today onward included), Download CSV via
`downloadReport`. Manager-visible (no `data-admin-only`).

## What is deliberately *not* changing

- No "recently joined" column/sort on the Households table (the
  table is a register lookup; the question is a report question).
- No welcome-letter mail-merge — the CSV is the merge source.
- No schema change.

## Verification plan

Matrix rows (`CR33-*`, in the CR-019 psql block after the donations
rows, reusing its `$RP` periods A = 2094-09-01..2095-08-31 and
B = 2095-09-01..2096-08-31, and `$T_SINGLE`; fixture households are
new and self-contained):

| Row | Check | Expect |
|---|---|---|
| CR33-01 | guest / member / noaud / manager / admin on the endpoint | 403 / 403 / 401 / 200 / 200 |
| CR33-02 | bad date, from > to | 400, 400 |
| CR33-03 | household N1 with a membership in A started 2095-03-15 (mid-period joiner) | present in `from=2095-03-01&to=2095-03-31`, Joined = 2095-03-15, Type SINGLE, Period "Rep A", Status PENDING_PAYMENT |
| CR33-04 | N1 also holds a B membership (rollover-style, 2095-09-01) | still ONE row, Joined stays 2095-03-15 |
| CR33-05 | household N2 whose only membership is period-start dated (2094-09-01) | absent from the March window; present in `from=2094-09-01&to=2094-09-01` |
| CR33-06 | header exact | `Household,Primary contact,Email,Phone,Joined,Type,Period,Status` |
| CR33-07 | N1's email/phone are the primary contact's | asserted |
| CR33-08 | order newest first | N1 (2095-03-15) before N2 (2094-09-01) in an open window `from=2094-09-01&to=2095-08-31` (both present) |
| CR33-09 | a household with no membership at all | absent (no joining date) |

Browser: Reports page → New households → From prefilled 90 days back →
Download → open.

## Verification results

**2026-09-21, dev stack (Opus 5).** Matrix +18 CR33-\* rows, all green
on the first run; full matrix **1049/16** (the 16 = the standing
calendar failures recorded in CR-032: the seed `2025-2026` period ended
2026-08-31). Playwright walkthrough
(`tmp/cr033-fixtures/cr033-walkthrough.js`) **9/0**: From prefilled to
today − 90 (local date), To blank, the request carries `from=`, the
download is `new-households.csv` with the exact header, the all-time
download lists the matrix's N1 fixture (`2095-03-15,SINGLE`), the
manager sees the block, zero page errors. Beyond the plan: CR33-01d
manager 200 (the report is manager territory), CR33-03b/c/d the joining
membership's type/period/status.

Note for the dev stack specifically: with no period covering today (the
same staleness), a wizard household created on dev today is dated
`2025-09-01` (`createForHousehold` falls back to the period start when
today is outside the period), so the 90-day default shows nothing on
dev; on prod, where 2026-27 exists, a wizard join is dated its day.
