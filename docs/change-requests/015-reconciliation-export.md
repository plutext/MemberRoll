# Change Request 015: Reconciliation Export — Xero-ready payment categorisation

**Status:** Proposed
**Date:** 2026-07-19
**Builds on:** [001](001-membership-register-data-layer.md) (the `payment`
table — including `reconciliation_status`, designed then and unused by
any code since: this CR is the consumer it was waiting for — and typed
`payment_allocation` rows), [003](003-renewals-and-manual-payments.md)
(manual payments, the commons-csv export pattern in
`AdminPeriodsResource`, corrections-are-negative-payments),
[004](004-stripe.md) (`external_transaction_id`, the DONATION/JOURNAL
allocation lines), [012](012-payment-receipts.md) (payer/household
attribution logic to reuse for the payer column).
**Feeds:** the recorded follow-up below (Stripe fee/payout capture);
indirectly the society's Xero bookkeeping, which stays outside the app.

## Problem

Money reaches the society's bank account carrying less information than
the treasurer needs to book it:

- A **Stripe payout** is several payments batched, net of fees. One
  bank line might be $213.70 for two memberships + a journal add-on +
  a donation − $6.30 of fees. Nothing on the bank line says so.
- A **bank transfer** is one line with whatever the member typed in the
  reference field. Which member, and membership vs donation, is a
  matching exercise.

Xero can't split or categorise these — its bank rules see only the bank
line. But MemberRoll holds the ground truth: every payment's allocation
split (MEMBERSHIP / JOURNAL / DONATION / OTHER), the Stripe transaction
id, the bank reference, the payer. Today that truth is visible only
payment-by-payment in the admin UI; the treasurer re-derives it monthly
by hand.

The roadmap put "accounting exports (Xero)" out of scope for v1. This
CR delivers the cheap four-fifths of that value — a categorised export
the treasurer books from — while keeping the expensive fifth (a Xero
API integration) out, deliberately.

## Objective

A treasurer sitting down with the Xero bank feed can, in minutes:

1. pull a CSV of payments for a chosen window (optionally: one method,
   or only not-yet-reconciled payments) with each payment's allocation
   split in columns, plus net totals by allocation type;
2. split/code the corresponding bank lines in Xero by transcribing the
   totals (or line-by-line for bank transfers, matched on reference);
3. mark the exported payments **reconciled**, so next month's
   "unreconciled only" export starts where this one ended — no
   remembered dates, no double-booking.

Negative payments (reversals, recorded Stripe refunds) ride along and
net against the totals — the export reflects the ledger, and the
ledger's correction model is CR-003's.

## Design

### 1. Two read endpoints, one write

All admin-only (`@RolesAllowed("admin")`), on `AdminPaymentsResource`
(the payments surface — not a new resource):

- **`GET /api/admin/payments/export/reconciliation.csv`**
  (`?from=YYYY-MM-DD&to=YYYY-MM-DD&method=STRIPE&unreconciledOnly=true`,
  all optional; `Produces("text/csv")`, commons-csv, the CR-003
  pattern). Detail columns:

  `Payment id, Received date, Method, Payer, Household, Gross,
  Membership, Journal, Donation, Other, Period, Bank reference,
  Stripe txn id, Status, Recorded by, Notes`

  — one row per payment (allocations of the same type summed into
  their column; the split columns sum to Gross), `Payment id` doubling
  as the CR-012 receipt number, amounts in dollars with sign (a
  refund row is simply negative). `Period` is the period name(s) of
  the MEMBERSHIP allocations, blank for pure donations. After the
  detail rows, a blank line then a labelled **summary block**: net
  total per allocation type, gross total, payment count, and the same
  broken out per method — the numbers the treasurer types into Xero's
  split dialog. Trailing summary rows in a CSV are the convention
  bank exports already follow; a spreadsheet user deletes them before
  sorting, a treasurer reads them first.

- **`GET /api/admin/payments/export/reconciliation`** — the same
  filters, JSON: `{count, maxPaymentId, totals: {byType, byMethod,
  gross}}`. The UI shows this before download (no client-side CSV
  parsing), and it carries `maxPaymentId` for the mark step.

- **`POST /api/admin/payments/reconcile`** with
  `{from?, to?, method?, maxPaymentId}` — sets
  `reconciliation_status = 'RECONCILED'` on **UNRECONCILED** payments
  matching the same filters with `payment_id <= maxPaymentId`, and
  returns `{marked: n}`. The `maxPaymentId` bound (required) is what
  the export handed back — a payment recorded between export and mark
  is never swept in unseen. Idempotent: re-posting marks 0. This is
  the first writer `reconciliation_status` has ever had; mutating it
  does not breach the insert-only discipline — the column is
  operational state, deliberately separated from the financial facts
  in CR-001's schema, and the payment rows themselves stay immutable.
  No unmark endpoint in v1: a mis-mark's remedy is the filterless
  export (everything is always still exportable); if unmark earns its
  keep it is a two-line follow-up.

### 2. UI: a Reconciliation card on the Renewals page

Next to the CR-003 exports (same page the treasurer already uses):
from/to date inputs, a method select (All/Stripe/Bank transfer/…), an
"unreconciled only" checkbox (default on), a **Preview** showing the
JSON summary (count + net totals by type), **Download CSV**, and —
enabled only after a preview — **Mark these reconciled**, which posts
with the previewed `maxPaymentId` and confirms with the marked count.
Payments already listed per-membership in the admin UI gain a small
RECONCILED badge (the CR-009 `.badge` pattern) so the state is visible
where payments are edited.

### 3. What the treasurer does in Xero (documented, not coded)

A short section in the user manual, not software: optionally enable
Xero's **native Stripe feed** (Stripe as its own account in Xero,
showing gross charges and fees — solves the payout-untangling half
with zero code from us); code the gross side from this CR's export
totals (membership income / donations / journal / fees accounts);
bank transfers coded line-by-line against the export's reference
column. MemberRoll deliberately knows **no Xero account codes** — the
mapping lives in the treasurer's head or Xero's own rules, so a chart
of accounts change never touches the app (the CR-014 "server knows no
vendors" principle).

## Why not alternatives

- **Full Xero API integration** (OAuth2 app, token refresh, account
  mapping UI, invoice-vs-bank-transaction modelling, drift when a push
  fails): the society's entire Stripe volume is a few payouts a month
  at peak. The integration's complexity is constant regardless of
  scale, so at this scale it never earns its keep — and a volunteer
  treasurer's hands staying on the books is a feature. Revisitable if
  reality disagrees; nothing here forecloses it.
- **Auto-marking payments reconciled at export time**: an export is a
  read; making it a write means a curious click silently consumes the
  "what's new" state. Separate, explicit, bounded mark step instead.
- **Importing the bank statement to match from our side**: the roadmap
  already rejected bank-feed reconciliation; this CR keeps the match
  in Xero where the bank data already is.
- **A summary-only report (no detail rows)**: bank transfers need
  line-level matching by reference; the detail rows are the point for
  that half.

## What this CR does NOT do

- **No Stripe fee / payout-id capture** — the recorded follow-up. The
  webhook event carries neither (the fee lives on the balance
  transaction, the payout id only exists at T+2), so capturing them
  means a second Stripe API surface (`payout.paid` webhook or per-
  payment balance-transaction fetch). With them, the export could
  group exactly by payout and pre-compute the net-to-the-cent bank
  line match. Design the columns so this slots in (payout id would
  become a grouping column); build it only if the treasurer finds the
  date-window workflow insufficient.
- **No Xero API, no account codes, no DGR donation receipts** (the
  roadmap's DGR caution stands: confirm the society's DGR status
  before donations become an official document anywhere).
- **No schema change** — `reconciliation_status` has waited since V1.
- **No unmark/edit of reconciliation state beyond the mark endpoint.**

## Verification plan

Fixtures: a household + membership with a mixed payment (MEMBERSHIP
4500 + JOURNAL 1000 + DONATION 500 in one payment), a plain bank
transfer with a reference, a negative STRIPE refund pair, an OTHER
allocation, spread across two received dates.

1. Role gates: export CSV / export JSON / reconcile → guest 403, user
   403, noaud 401, admin 200 (the standing triple, ×3 endpoints).
2. CSV shape: header row exact; the mixed payment appears once with
   4500/1000/500 in the right columns and Gross = 6000; the split
   columns sum to Gross on every row; `text/csv` content type.
3. The refund row is negative and the summary nets it; summary block
   totals equal the seeded arithmetic exactly (byType, byMethod,
   gross, count) in both CSV trailer and JSON.
4. Filters: `from`/`to` window excludes the out-of-window fixture;
   `method=STRIPE` excludes the bank transfer; combined filters
   compose.
5. Reconcile: JSON preview returns `maxPaymentId`; a payment recorded
   AFTER the preview (higher id, in-window) survives the mark
   unmarked; re-POST marks 0 (idempotence); `unreconciledOnly=true`
   export then excludes the marked rows and includes the straggler;
   psql confirms exactly the expected rows flipped RECONCILED.
6. Validation: `maxPaymentId` absent → 400; bad dates → 400.
7. Regression: the whole matrix stays green (dev invocation).
8. Browser walkthrough (Playwright + eyeball): preview totals render,
   CSV downloads and opens, mark button gated on preview, confirmation
   count, RECONCILED badge appears on the payment list.

Matrix rows land as `CR15-*` in `verify-matrix.sh`, self-cleaning
(fixtures keyed by `$$`; reconciliation marks are confined to fixture
payments — the mark endpoint's filters make that natural).

## Open questions

- Does the treasurer want a per-period membership-income split (income
  by 2025-26 vs 2026-27 within one window — matters at rollover when
  both years' payments arrive together)? The `Period` column carries
  the data; the question is whether the summary block should subtotal
  by it. Cheap to add now if wanted.
- Confirm with the treasurer that trailing summary rows in the CSV are
  helpful rather than annoying (the alternative: two files, or
  summary-in-UI only).

## Results

*(to be recorded during implementation)*
