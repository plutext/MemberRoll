# CR 003: Renewals and manual payments

Status: VERIFIED

## Problem

The register now holds the society's people, households and imported
memberships (CR-001/002), but it cannot yet run a membership year: there
is no way to open the next period, generate the renewals, record the
bank transfers and cash that arrive, see who has paid, or produce the
AGM voting register. Those are the operations the treasurer's
spreadsheet performs today — after this CR the app replaces that
spreadsheet-and-bank-statement process end to end, with zero
member-facing features (the roadmap's ordering principle).

Roadmap scope (CR-003): period rollover, cash/cheque/transfer payment
entry, financial-status filters, CSV exports.

**No schema change.** CR-001 deliberately shipped the whole minimum
schema; this CR is stores, API and admin UI over the existing tables.
The one structural payoff claimed then comes due now: payment
correctness (rule 6 — paid-ness derives from `payment_allocation`) and
rollover uniformity (life/honorary = zero-due ACTIVE rows) were designed
in; CR-003 just exercises them.

## Approach

### Stores (and the CR-002 refactor follow-up)

Three new package-private stores in the CR-001 pattern (hand-written
SQL, explicit lambda row mappers, Java records):

- `PeriodStore` — periods with their per-type prices.
- `MembershipStore` — memberships + membership_person, the financial
  status query, rollover.
- `PaymentStore` — payments + allocations, derived paid amounts.

Lesson from CR-002 surprise #2 applied structurally: **write methods
take an explicit `Handle`**, and the resource opens the transaction —
so stores compose inside one transaction instead of each opening their
own connection. Read paths get `Jdbi`-taking conveniences.
`ImportService`'s direct membership/payment SQL moves into these stores
(the flagged CR-002 follow-up); behaviour unchanged, the existing
matrix guards it.

Derived paid amount, used everywhere below:
`amount_paid_cents(m) = SUM(payment_allocation.amount_cents WHERE
allocation_type = 'MEMBERSHIP' AND membership_id = m)` — JOURNAL /
DONATION / OTHER allocations never count toward the membership fee.

### Periods: create, list, reprice

| method | path | behaviour |
|---|---|---|
| GET | `/api/admin/periods` | all periods, newest first, each with its prices and membership counts by status |
| POST | `/api/admin/periods` | `{name, startDate, endDate, renewalOpenDate?, lateJoiningCutoff?, prices:[{type,amountCents}...]}` → 201. A price is required for **every** membership type (409 duplicate name; 400 missing price — rollover must never fail later for want of a price row) |
| PUT | `/api/admin/periods/{id}` | dates and prices. Price changes do NOT touch existing memberships' `amount_due_cents` (that is a snapshot, by design); with `?repriceUnpaid=true` the response also re-snapshots `amount_due_cents` for memberships of that period **with zero allocations** — the recovery path for "rolled over before noticing the fee rise" |

Period creation is deliberately a separate, human step from rollover:
the moment the prices are confirmed with the society is exactly the
moment they are typed in.

### Rollover: preview, then apply

Same two-phase shape as the CR-002 import — a treasurer sees the counts
before anything is written.

| method | path | behaviour |
|---|---|---|
| POST | `/api/admin/periods/{id}/rollover/preview` | report only, writes nothing |
| POST | `/api/admin/periods/{id}/rollover` | same computation; applies in one transaction |

Semantics: for every household with an **ACTIVE** membership in the
*source* period (default: the period with the latest `start_date`
before the target's — named in the report so the human confirms;
overridable via `?from={periodId}`), create a membership in the target
period:

- same membership type; `amount_due_cents` from the target period's
  `membership_type_price`;
- `status = PENDING_PAYMENT` — except **zero-due types (life/honorary):
  ACTIVE immediately**, `approved_date` = rollover date, per the schema
  doc's uniformity rule (no special cases in "who is financial"
  queries, and no payment row — `payment.amount_cents <> 0` forbids $0
  payments anyway);
- `start_date`/`end_date` from the target period;
- `membership_person` rows from the household's **current**
  composition (`left_household_date IS NULL`, not deceased) — the new
  year reflects who is in the household now, exactly as the import
  does: MEMBER/PARTNER get statutory/voting/committee true, DEPENDANT/
  OTHER false; `membership_role` = the relationship type.

Skipped (reported, not errors): households that already have a
membership in the target period (early manual renewals — the CR-001
unique constraint backstops this, making **re-running a rollover safe
and convergent**), and households with no current members. Errors
(block apply, 400): none expected given period creation requires full
prices; the report keeps the errors array anyway for shape-consistency
with import.

PENDING_PAYMENT, LAPSED and CEASED source memberships do not roll over
— an unpaid year does not beget a new invoice; lapsed members return
via a manually created membership (below) or, later, CR-007.

### Memberships: create, view, transition

| method | path | behaviour |
|---|---|---|
| POST | `/api/admin/memberships` | `{householdId, membershipPeriodId, membershipTypeId, startDate?}` → 201 PENDING_PAYMENT (ACTIVE if zero-due), `amount_due_cents` snapshotted, `membership_person` from current household composition, `application_date` = today. Default `start_date` = today clamped into the period. 409 if the household already has a membership that period. Response carries a **warning** when today is past the period's `late_joining_cutoff` ("consider the next period") — the 1 July rule stays configuration + human judgement, not hidden automation |
| GET | `/api/admin/memberships/{id}` | membership + people + payments/allocations + derived paid |
| PUT | `/api/admin/memberships/{id}` | exactly one of: `{membershipTypeId}` (re-snapshots `amount_due_cents`; **400 once any allocation exists** — repricing under money is how registers drift); `{status:"CEASED", ceasedDate, cessationReason}`; `{status:"LAPSED"}` (from PENDING_PAYMENT); `{status:"PENDING_PAYMENT"}` (undo lapse) |
| POST | `/api/admin/periods/{id}/lapse-unpaid` | bulk: every PENDING_PAYMENT membership of the period → LAPSED; returns the count. Explicit treasurer action (typically after the grace period), never time-triggered |

Deliberately **no hand-set ACTIVE**: activation only ever follows from
allocations covering `amount_due_cents` (rule 6 kept honest). Zero-due
creation is the one exception and it is the same rule (0 ≥ 0).

### Payments: record, list — corrections are negative payments

| method | path | behaviour |
|---|---|---|
| POST | `/api/admin/payments` | `{receivedDate, amountCents, method, payerPersonId?, bankReference?, notes?, allocations:[{type, membershipId?, amountCents}...]}` → 201. `recorded_by` = the authenticated admin's username, `recorded_at` = now (audit trail). 400 unless allocations sum exactly to `amountCents`, and every MEMBERSHIP/JOURNAL-riding allocation names a membership |
| GET | `/api/admin/payments?membershipId=&householdId=&periodId=&limit=&offset=` | payments joined to household names, allocations nested |

Methods: CASH / CHEQUE / BANK_TRANSFER / OTHER (STRIPE arrives with
CR-004's webhook and is never hand-entered). Multiple allocation lines
are in scope now — today's real bank transfers already combine
membership + donation ("$75 = $65 household + $10 donation"), and the
schema was built for it; the UI defaults to a single MEMBERSHIP line
prefilled with the balance due.

**After every payment write**, each membership touched by a MEMBERSHIP
allocation is recomputed in the same transaction:

- paid ≥ due and status PENDING_PAYMENT or LAPSED → **ACTIVE**
  (`approved_date` coalesced to the received date — a late payment
  reactivates a lapsed membership);
- paid < due and status ACTIVE → back to **PENDING_PAYMENT**;
- CEASED is never touched by recompute.

Overpayment is allowed (a warning in the response, not an error — the
treasurer decides; the surplus is visible as paid > due).

**Corrections**: no payment UPDATE or DELETE, ever. The
`amount_cents <> 0` CHECK deliberately admits negatives: a mistake is
fixed by an equal-and-opposite payment (the UI's per-payment "Reverse"
button prefills one, notes "reversal of payment #N"), and the recompute
above demotes a wrongly-activated membership automatically. Insert-only
bookkeeping, same as the rest of the register.

### Financial status view

The treasurer's working screen, one query:

| method | path | behaviour |
|---|---|---|
| GET | `/api/admin/periods/{id}/memberships?status=&type=&q=&limit=&offset=` | rows: membership id, household + primary contact, member names, type, status, `amountDueCents`, derived `amountPaidCents`; `q` matches household/member names; plus an unfiltered `summary`: counts by status, total due, total collected |

`status` filters on the stored membership status — the UI labels them
Unpaid (PENDING_PAYMENT), Paid (ACTIVE), Lapsed, Applied (populated
from CR-007), Ceased. Paid amounts are displayed even when the filter
makes them predictable, so a partial payment is always visible.

### CSV exports

Admin-only GETs returning `text/csv` (filename via
Content-Disposition), all per period:

| path | one row per | columns |
|---|---|---|
| `/api/admin/periods/{id}/export/agm-register.csv` | voting member (`membership_person.has_voting_rights`, membership ACTIVE) | family name, given name, household, membership type |
| `/api/admin/periods/{id}/export/mailing-labels.csv` | household with an ACTIVE membership | contact/household name, preferred POSTAL address lines, locality, state, postcode — households **without** a postal address are included with blank address columns (surfaced, not silently skipped — the roadmap's stated principle) |
| `/api/admin/periods/{id}/export/financial.csv` | membership (the status view, unpaginated) | household, contact, type, status, due, paid |

Bearer-token note: static pages have no cookies, so the admin page
downloads these with `fetch` + Authorization header → Blob → temporary
object URL, not a plain `<a href>`.

### Admin UI

New **Renewals** section in `admin/` (same client-side gating +
`@RolesAllowed` server half, same classic-script style):

- period dropdown + summary strip (counts by status, collected/due);
- the financial table: search box, status filter, per-row **Record
  payment** (prefilled form: balance due, method, date, reference,
  optional extra allocation lines) and status actions (lapse / undo /
  cease-with-reason);
- payments list per membership with the **Reverse** button;
- **New period** form (dates + a price input per type) and **Rollover**
  block (preview report → confirm apply), mirroring the import UX;
- export buttons;
- "Lapse all unpaid" with a confirm step.

Household detail (existing panel) gains "New membership" (period +
type) so a returning or brand-new member can be signed up mid-year.

### Rejected alternatives

- **Time-based auto-lapse (scheduler)** — lapsing is a governance
  statement with a society-decided grace period; an explicit bulk
  action is one click a year and keeps the war scheduler-free.
- **Stored `amount_paid` / hand-settable ACTIVE** — schema rule 6;
  derivation plus recompute is the whole point of the allocation table.
- **Payment edit/delete** — negative correcting entries preserve the
  audit trail (recorded_by/recorded_at on both entries); a mutable
  payments table is how spreadsheets lie.
- **Dedicated reversal endpoint** — UI sugar over an ordinary negative
  payment; one code path, no "already reversed?" bookkeeping.
- **Rolling over PENDING_PAYMENT source memberships** — would invoice
  people who never paid the prior year; returning members are a
  deliberate act (manual membership now, CR-007 later).
- **Auto-creating the target period at rollover** — prices must be
  consciously confirmed (the V2 seed's price placeholders are still
  flagged); a wrong-price rollover is exactly the failure mode the
  preview + `repriceUnpaid` pair exists to catch and repair.
- **Journal add-on pricing** — CR-004 (it ships with the pay page);
  the JOURNAL allocation type is accepted from day one so a combined
  manual payment can already be recorded faithfully.

## Verification plan

Scripted (extend `server/verify-matrix.sh`; fixtures under
`tmp/cr003-fixtures/`, unique per-run family names as before). The
fixture script seeds a LIFE type (price 0 for the target period) via
psql — there is deliberately no type-management API.

| # | caller | call | expect |
|---|---|---|---|
| 1 | guest | GET /api/admin/periods | 403 |
| 2 | testuser | POST /api/admin/payments | 403 |
| 3 | test-cli-noaud | GET /api/admin/periods | 401 |
| 4 | testadmin | GET /api/admin/periods | 200, contains seeded 2025-2026 with SINGLE 4500 / HOUSEHOLD 6500 |
| 5 | testadmin | POST /api/admin/periods {name:"2027-2028", dates, prices for SINGLE+HOUSEHOLD+LIFE} | 201 |
| 6 | testadmin | POST same name again | 409 |
| 7 | testadmin | POST /api/admin/periods missing a type's price | 400 naming the type |
| 8 | testadmin | POST /api/admin/memberships {household w/o membership, SINGLE, 2025-2026} | 201 PENDING_PAYMENT, amount_due 4500; psql: membership_person flags true for MEMBER, false for DEPENDANT |
| 9 | testadmin | POST again same household+period | 409 |
| 10 | testadmin | PUT /api/admin/memberships/{id} {membershipTypeId:HOUSEHOLD} | 200, amount_due re-snapshots to 6500 |
| 11 | testadmin | POST /api/admin/payments — partial (3000 of 6500, BANK_TRANSFER, MEMBERSHIP allocation) | 201; membership stays PENDING_PAYMENT; GET shows amountPaidCents 3000 |
| 12 | testadmin | PUT type change now | 400 (allocations exist) |
| 13 | testadmin | second payment 3500 | 201; membership ACTIVE; psql: approved_date = received date |
| 14 | testadmin | POST /api/admin/payments allocations sum ≠ amount | 400 |
| 15 | testadmin | negative payment −3500 (reversal) | 201; membership back to PENDING_PAYMENT; paid 3000 |
| 16 | testadmin | payment 3500 + extra DONATION allocation line (sum matches) | 201; membership ACTIVE; DONATION allocation does NOT count toward paid (psql) |
| 17 | testadmin | GET /api/admin/periods/{2025-2026}/memberships?status=ACTIVE&q={run family} | 200, row shows due 6500 paid 6500; summary counts consistent |
| 18 | testadmin | POST /api/admin/periods/{id}/lapse-unpaid | 200 count ≥ 1; lapsed membership listed under status=LAPSED |
| 19 | testadmin | payment covering a LAPSED membership | 201; status ACTIVE (late payment reactivates) |
| 20 | testadmin | PUT {status:"CEASED", ceasedDate, cessationReason:"RESIGNED"} | 200; psql: ceased_date set; subsequent payment recompute leaves CEASED untouched |
| 21 | testadmin | rollover **preview** into 2027-2028 | 200; toCreate counts the run's ACTIVE households (incl. a psql-seeded LIFE household), skips the household that already renewed; writes nothing |
| 22 | testadmin | rollover apply | 200; created == preview toCreate; psql: new memberships PENDING_PAYMENT at 2027-2028 prices, LIFE household ACTIVE amount_due 0 with no payment row; membership_person copied from current composition (a left member absent) |
| 23 | testadmin | rollover apply again | 200; created 0, all skipped |
| 24 | testadmin | PUT period `?repriceUnpaid=true` with changed price | 200; zero-allocation memberships re-priced, part-paid one untouched (psql) |
| 25 | testadmin | GET export/agm-register.csv | 200 text/csv; contains the ACTIVE voting member, excludes the DEPENDANT and the lapsed household |
| 26 | testadmin | GET export/mailing-labels.csv | 200; household with address has its lines; addressless household present with blank columns |
| 27 | testadmin | GET export/financial.csv | 200; row count = memberships in period |
| 28 | guest | GET export/agm-register.csv | 403 |

Import-refactor guard: the existing 130-check matrix (esp. CR-002 cases
12–15, membership/payment side effects) must stay green after
`ImportService` moves onto the new stores.

Browser walkthrough: admin/ as testadmin → Renewals → create 2027-2028
with prices → rollover preview shows counts → apply → filter Unpaid →
record a bank transfer with reference → row flips to Paid → reverse it
→ flips back → record correct amount + donation line → lapse-all on
the stragglers → download all three CSVs and open them; household
detail → New membership for a fresh household. Confirm testuser sees
the access-denied state.

No compose/auth/Caddy changes → no deploy Local smoke required.

## Results

Implemented 2026-07-17. Scripted verification (`server/verify-matrix.sh`
against the dev stack on the 18xxx ports, with psql side-effect checks):

```
PASS=202 FAIL=0
```

- The pre-CR-003 baseline (130 checks, CR-001 register + CR-002 import,
  incl. cases 12–15 / 75–79 membership and payment side effects) stays
  **green after `ImportService` moved onto the new stores** — the import
  refactor guard held.
- All 28 planned CR-003 cases pass, scripted as `CR3-01`…`CR3-28`
  (several with sub-checks, e.g. `CR3-08d/e` the membership_person flag
  side effects, `CR3-13c` approved_date = received date, `CR3-16c/d`
  the JOURNAL/DONATION-does-not-count derivation, `CR3-22c–f` the
  rollover LIFE/left-member/HH_B side effects via psql). Fixtures use a
  unique per-run tag (`Ren$$`) and seed the LIFE type + a departed-member
  LIFE household via psql, so re-runs converge.

Code shape as built (matches the approach, with these notes):

- Three stores in the CR-001 pattern — `PeriodStore`, `MembershipStore`,
  `PaymentStore` — write methods take an explicit `Handle`, read methods
  open their own; resources own the transaction and compose across stores
  (a payment insert + the per-membership `recompute` run in one
  transaction). `ImportService`'s membership/payment SQL now calls
  `MembershipStore.insertMembership`/`insertMembershipPerson` and
  `PaymentStore.insertImportPayment`.
- Resources: `AdminPeriodsResource` (periods CRUD, rollover preview/apply,
  lapse-unpaid, the financial-status view, the three CSV exports),
  `AdminMembershipsResource` (create / detail / transition),
  `AdminPaymentsResource` (record / list). Two small leaf helpers added:
  `ConflictException` (→ 409) and `Payloads` (request-body parsing for
  the richer payloads), alongside the existing `AdminPeopleResource`
  helpers reused for JSON output.
- CSV exports use commons-csv's `CSVPrinter` (already a dependency) for
  correct quoting; the admin page downloads them with `fetch` +
  Authorization header → Blob (static pages have no cookies).

Divergences from the written spec, all minor:

- `PUT /api/admin/periods/{id}` treats dates as optional (omitted → keep
  existing) rather than requiring them, so a pure reprice needs no dates;
  prices are upserted per provided type.
- `changeType` runs a `recompute` afterwards so retyping a zero-allocation
  membership to a zero-due type activates it (0 ≥ 0), keeping rule 6
  honest without a special case.
- `POST /api/admin/payments` returns `{id, memberships:[…fresh
  status/due/paid…], warnings:[…]}` rather than echoing the whole
  payment — the UI refreshes the table from it; the overpayment warning
  rides `warnings`.

**Browser walkthrough: done** (Jason, 2026-07-18) — the admin panel drove
the flow end to end (create period → rollover → record a transfer →
reverse → lapse-all → download the three CSVs; household-detail "New
membership"), which is what moves this CR to VERIFIED.

Post-implementation refinements made while walking it through (same
session, all matrix-green):

- The seed period is now **2025-2026** (1 Sep 2025 – 31 Aug 2026); the
  society's membership year runs September–August (`V2__reference_data.sql`).
- The admin panel was split into menu-driven pages (`admin/index.html`
  register + renewals, `admin/users.html`, `admin/import.html`) sharing
  `admin.css` and a page-aware `admin.js` — CSV import is a one-off, and
  Users is a separate concern, so both moved off the main page.
- New-period form pre-fills from the selected period (dates +1 year,
  prices carried over); the import Target-period field became a dropdown
  of existing periods (with an empty-state prompt when none exist).
- The role×endpoint matrix now stands at **205 checks** (the extra static-
  page checks for the new admin pages); rollover cases pin the source
  period with `?from=` so the matrix stays re-runnable against a dev DB
  that holds periods created by hand.

A treasurer-facing guide covering all of the above lives in
`docs/user-manual.md`.

## Follow-ups / amendments

- Before the first real rollover: confirm 2027-28 prices and period
  dates with the society (the standing CR-001 flag), and ask what grace
  period should pass before "Lapse all unpaid" is run.
- CR-004 will extend `PaymentStore` with STRIPE payments keyed by
  `external_transaction_id` (idempotent webhook) and price the JOURNAL
  add-on.
- APPLIED memberships (CR-007) will surface in the existing status
  filter with no further work here.
