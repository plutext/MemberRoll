# Change Request 015: Reconciliation Export — Xero-ready payment categorisation

**Status:** Implemented and verified (2026-07-19)
**Date:** 2026-07-19 (revised same day at review: the Xero side is now
built around the clearing-account + manual-journal pattern — §3 — and
the CR gains an optional ready-to-import journal CSV; the original
"split each payout line by hand in Xero" workflow moved to the
rejected-alternatives list)
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
2. book the Stripe side with **one manual journal** (typed from the
   summary totals, or imported from the generated journal CSV) against
   a clearing account the bank feed's payout lines drain into (§3) —
   bank transfers stay line-by-line, matched on the reference column;
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

- **`GET /api/admin/payments/export/xero-journal.csv`** — the same
  filters, emitting Xero's **manual-journal import format** (one line
  per journal row: Narration, Date, AccountCode, TaxRate, Amount —
  positive = debit, negative = credit; a shared Narration + Date
  groups the lines into one journal on import). The journal it writes
  is §3's: debit the clearing account for the gross Stripe total,
  credit each income account its net type total (a refund-heavy
  window flips a line's sign naturally — Xero reads a negative amount
  as the other side). Only STRIPE-method payments belong in this file
  (the clearing pattern is a payout pattern); the endpoint forces
  `method=STRIPE` regardless of the filter given. Answers **409 until
  the account mapping below is saved** — never a guessed account code.

- **`GET`/`PUT /api/admin/payments/xero-account-mapping`** — one
  `app_setting` row (`xero_accounts`, a JSON blob: account codes for
  membership / journal / donation / other income and the clearing
  account, plus the tax-rate string, e.g. `BAS Excluded`). The CR-014
  `smtp_settings` pattern exactly: settings change as a unit, one
  atomic value, no migration; absent = the journal CSV feature is
  dormant and everything else works. The codes are **opaque strings**
  — the server validates presence, not chart-of-accounts sense; a
  wrong code fails loudly at Xero's import, the right place.

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
JSON summary (count + net totals by type), **Download CSV**,
**Download Xero journal** (shown only when the account mapping is
saved; an **Accounts…** button opens the mapping dialog — the CR-014
settings-form pattern), and — enabled only after a preview — **Mark
these reconciled**, which posts with the previewed `maxPaymentId` and
confirms with the marked count.
Payments already listed per-membership in the admin UI gain a small
RECONCILED badge (the CR-009 `.badge` pattern) so the state is visible
where payments are edited.

### 3. The Xero pattern: clearing account + monthly manual journal

This is the load-bearing design of the CR — the software above exists
to feed it. It goes in the user manual as the documented procedure;
one-time Xero setup plus a monthly rhythm.

**Why a clearing account at all.** Xero manual journals cannot post to
bank-type accounts — a deliberate Xero rule (bank balances must come
from statements). So the money's arrival and its categorisation are
forced into two separate, individually simple steps, hinged on an
intermediate account:

**One-time setup (treasurer, in Xero):**

- Add a **"Stripe Clearing"** account — type *Current Asset* (not
  bank-type; that's what makes it journal-able).
- Add a **bank rule** on the society's bank account: any line from
  Stripe (the payout descriptions are uniform) → code the whole line
  to Stripe Clearing. No splitting, no judgement — so the rule can do
  it, and the bank-feed side of Stripe reconciliation becomes
  one-click confirmations.
- Have the income accounts ready (membership, donations, journal — and
  a Stripe-fees expense account), and enter their codes plus the
  clearing account's into MemberRoll's account mapping (§1).

**Monthly rhythm:**

1. Bank lines: the rule has coded every Stripe payout to the clearing
   account; confirm them. (Bank transfers are unaffected by any of
   this — they remain individual lines, coded line-by-line against the
   detail export's reference column.)
2. In MemberRoll: preview the window (unreconciled STRIPE payments),
   download the journal CSV, mark reconciled.
3. In Xero: import the journal (Accounting → Manual Journals →
   Import), review, post. Shape, using the summary's numbers:

   | Line | Debit | Credit |
   |---|---|---|
   | Stripe Clearing | gross total | |
   | Membership income | | net membership total |
   | Donation income | | net donation total |
   | Journal income | | net journal total |
   | Stripe fees (expense) | month's fees | |
   | Stripe Clearing | | month's fees |

   (The fee pair is the treasurer's own line until the fee-capture
   follow-up lands — see below. A refund-heavy window can flip an
   income line to the debit side; the generated CSV's signed amounts
   already say so.)
4. **Read the clearing account's balance. Zero is the proof.** The
   bank feed drained *net payouts* out of clearing; the journal pushed
   *gross − fees* in. If every payment was recorded, every payout
   banked, and the fees right, they cancel exactly — and a non-zero
   remainder is localised to one month's window with a known set of
   payments (the export) and payouts (the bank statement) to compare,
   instead of being smeared invisibly across dozens of hand-split
   bank lines. This audit property is the pattern's real payoff; the
   reduced clicking is a bonus.

**Fees, until the follow-up.** MemberRoll doesn't yet know Stripe's
fees, so the generated journal covers the gross splits and the
clearing account is left holding exactly the window's fees. Two
equally fine ways to clear it: the treasurer adds the fee pair to the
imported journal from Stripe's dashboard total for the month, or the
society enables **Xero's native Stripe feed** (Stripe as its own
account in Xero, gross charges + fees) and lets it book the fees. With
the fee/payout capture follow-up built, the generated journal includes
the fee lines itself and clearing zeroes with no hands at all.

**On account codes in the app.** CR-014's "the server knows no
vendors" principle bends here, deliberately and narrowly: generating
an importable journal needs the society's codes, so they live in one
admin-edited settings blob as opaque strings. The server still knows
no Xero semantics — no API, no validation of the chart of accounts, no
behaviour keyed on what a code means. A chart change is a settings
edit, never a code change; with the mapping unset, the feature is
dormant and the plain CSV workflow stands alone.

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
- **Splitting each payout bank line by hand in Xero** (this CR's own
  first draft): works, but every payout is a multi-way split done
  under time pressure, errors hide inside individual lines, and
  nothing ever proves the month reconciled. The clearing-account
  pattern replaces N splits with one rule + one journal and adds the
  zero-balance proof — same data, strictly better ergonomics.
  Superseded at review, same day.
- **Importing the bank statement to match from our side**: the roadmap
  already rejected bank-feed reconciliation; this CR keeps the match
  in Xero where the bank data already is.
- **A summary-only report (no detail rows)**: bank transfers need
  line-level matching by reference; the detail rows are the point for
  that half.

## What this CR does NOT do

- **No Stripe fee / payout-id capture** — the recorded follow-up, now
  with a sharper payoff than when first noted: the webhook event
  carries neither (the fee lives on the balance transaction, the
  payout id only exists at T+2), so capturing them means a second
  Stripe API surface (`payout.paid` webhook or per-payment
  balance-transaction fetch). With them, the generated journal gains
  its fee lines and §3's clearing account zeroes with no manual fee
  entry — the fully mechanical month. Until then the fee residue is
  the treasurer's one hand-entered number. Design the columns so this
  slots in (payout id as a grouping column, fees as a summary line);
  build it when the treasurer's first few real months say it's worth
  a second Stripe surface.
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
6. Journal CSV: 409 with no mapping saved; after PUT mapping, the file
   **balances** (sum of signed amounts = 0 is asserted arithmetically),
   carries one shared Narration+Date, uses exactly the mapped codes
   and tax-rate string, includes only STRIPE payments even when called
   with `method=BANK_TRANSFER`, and a refund-dominated fixture window
   flips the affected income line's sign. Mapping endpoints get the
   standing role-gate triple; PUT with a missing code → 400; GET
   echoes the blob.
7. Validation: `maxPaymentId` absent → 400; bad dates → 400.
8. Regression: the whole matrix stays green (dev invocation).
9. Browser walkthrough (Playwright + eyeball): preview totals render,
   CSV downloads and opens, Accounts… dialog saves and reveals the
   journal button, mark button gated on preview, confirmation count,
   RECONCILED badge appears on the payment list. Real-Xero import of
   a generated journal file is a go-live-time check with the
   treasurer (records in Results when it happens), not a dev-loop
   gate.

Matrix rows land as `CR15-*` in `verify-matrix.sh`, self-cleaning
(fixtures keyed by `$$`; reconciliation marks are confined to fixture
payments — the mark endpoint's filters make that natural).

## Open questions (for the treasurer)

Answered 2026-07-19:

- **Clearing account + bank rule: agreed** — the treasurer is happy to
  add the Stripe Clearing account and the payout bank rule per §3.
- **Not registered for GST** — so the journal's tax treatment is the
  simple case: `BAS Excluded` on every line, and it becomes the
  mapping form's pre-filled default (still stored in the blob, still
  editable — registration status can change; no code keys on it).

Still open (none blocks implementation — the mapping is runtime
config, and the two format questions have safe defaults):

- The actual account codes for membership / donations / journal /
  other income, the clearing account, and fees — entered into the
  mapping form whenever they arrive; the journal export stays dormant
  until then by design.
- Per-period membership-income split in the summary/journal (matters
  at rollover when two years' payments share a window). Default:
  not subtotalled; the `Period` column carries the data either way.
- Trailing summary rows in the detail CSV vs summary-in-UI only.
  Default: trailing rows, clearly labelled.

## Results

**Implemented and verified 2026-07-19** (Opus 4.8, 1M).

### What shipped

- **`ReconciliationStore`** — the export query (one `Acc` per payment, base
  rows materialised then allocations folded into the MEMBERSHIP/JOURNAL/
  DONATION/OTHER columns + household/period sets) and the bounded `reconcile`
  UPDATE. The same `Filter` (from/to/method/unreconciledOnly) drives both.
- **`XeroAccounts`** — the `xero_accounts` `app_setting` blob (five opaque
  codes + tax rate), `read()` returns empty unless all five codes present
  (feature dormant → journal 409). CR-014's `smtp_settings` pattern exactly.
- **`AdminPaymentsResource`** gained the two reads (`export/reconciliation.csv`
  with the trailing summary block, `export/reconciliation` JSON), the
  `export/xero-journal.csv` (STRIPE-forced, 409 without mapping, balanced by
  construction — clearing +gross debit vs each income −net credit, zero lines
  skipped), `GET`/`PUT xero-account-mapping`, and `POST reconcile`.
- **`PaymentStore.Payment`** gained `reconciliationStatus` (list/find/attach +
  `paymentJson`), surfaced as a **RECONCILED** badge on the payment list.
- **UI** — the Reconciliation card on the Renewals page (from/to/method/
  unreconciled-only, Preview, Download CSV, Accounts… dialog, Download Xero
  journal shown only when mapped, Mark-gated-on-preview) in `index.html` +
  `admin.js`.

The treasurer's answers landed as built-in defaults: `BAS Excluded` prefills
the mapping form's tax rate; the account codes stay blank until entered (the
journal export is dormant until then, by design).

### Verification

- **Matrix**: 63 `CR15-*` rows, self-cleaning (2098/2099 windows unique to the
  CR, every export/journal call passes `unreconciledOnly=true`, the block ends
  by marking the whole window reconciled + dropping the mapping — a re-run
  starts with zero unreconciled residue and no saved mapping so the 409 test
  stays valid). Covers: the role-gate triple ×5 surfaces; validation
  (`maxPaymentId` required, bad date/method 400); CSV header + content-type +
  the mixed-payment split (45/10/5 → gross 60) + every-row split-sums-to-gross
  + negative refund row + summary block; filters (narrow date, `method=STRIPE`
  excludes bank, combined); the journal (409 → PUT mapping → **balances to
  0.00** arithmetically, 4 lines, clearing debit = gross, membership credit,
  tax rate every line, one shared narration, STRIPE forced even with
  `method=BANK_TRANSFER`, and a refund-only April window flipping the
  membership line to the debit side); reconcile (marks exactly the 5 ≤
  `maxPaymentId`, a straggler recorded after preview survives, re-post marks 0,
  the unreconciled window then shows only the straggler). **Whole matrix
  PASS=602 FAIL=1** — the one failure (`27b list has testuser`) is the
  pre-existing Keycloak-listing flake (the admin/users list caps at 50 and
  testuser has been pushed past it by accumulated KC accounts in the long-lived
  dev stack; testuser authenticates fine throughout), unrelated to this
  additive CR.
- **Browser (Playwright + eyeball)**: 17/17 — preview totals render
  ($50.00 gross, membership $45.00, donation $5.00), CSV downloads and carries
  the payment row, Accounts… dialog saves and reveals the journal button (tax
  rate prefilled `BAS Excluded`), journal downloads, Mark gated on preview and
  confirms "Marked 1 payment", and the **RECONCILED badge** lands on the
  payment in its membership detail. `tmp/cr015-fixtures/cr015-walkthrough.js`.

### A gotcha worth recording

The matrix fixtures seed STRIPE payments via psql (positive STRIPE can't be
POSTed — webhook-only). `psql -tAc "INSERT … RETURNING payment_id"` prints the
value **and** the `INSERT 0 1` command tag, so a naive `$(…)` capture yields
`"235\nINSERT 0 1"` — which then corrupts the dependent allocation inserts
(they silently fail → the type columns read 0). `pay()` filters to the numeric
line (`grep -oE '^[0-9]+' | head -1`).

### Real-Xero import (deferred to go-live)

Importing a generated journal into the society's actual Xero is a
go-live-time check with the treasurer (records here when it happens), not a
dev-loop gate — as the plan noted. The account codes and the two format
questions (per-period subtotal, trailing-summary shape) remain the treasurer's
to answer; the mapping form and the CSV work with the safe defaults until then.
