# CR 018: Life membership — instantiate the LIFE type, set/unset interface, type filter

Status: PROPOSED (2026-07-23 — not yet implemented)

## Problem

The schema has supported life membership since CR-001 as a designed
convention: a `membership_type` whose per-period price is $0 creates
memberships that are ACTIVE immediately (`MembershipStore.
createForHousehold`), and the rollover carries them as a fresh zero-due
ACTIVE membership each period, so "financial for period X" stays one
uniform join (docs/membership_management_database_schema.md, "Life and
honorary"). But only SINGLE and HOUSEHOLD were ever seeded (V2), there
is deliberately no type-management endpoint (CR-010 decision), and no
admin UI exists to change a membership's type at all — the server-side
`changeType` (PUT `/api/admin/memberships/{id}` with
`membershipTypeId`) has no caller in the panel.

Meanwhile the society **has life members**. The source spreadsheet
(`ydhs/Membership 2025-2026 as at 6 June 2026.csv` — gitignored, names
deliberately not reproduced here) marks 8 households LIFE, but the file
actually imported
(`ydhs/Membership import simple.csv`) carried them as ordinary
SINGLE/HOUSEHOLD with `paid=yes`, because there was no LIFE type to
import into. The import therefore fabricated a full-fee OTHER payment
("CSV import" note) with a MEMBERSHIP allocation for each. Left as-is,
the 2026-27 rollover will create real-money PENDING_PAYMENT memberships
for the life households and renewal segment email will chase them.

A second bite: `changeType` refuses once **any** `payment_allocation`
row exists for the membership ("repricing under money is how registers
drift"). A reversal is itself an allocation, so even
reverse-the-import-payment-then-retype is refused under the current
guard. Fixing the 8 households needs a deliberate design change, not
just data entry.

Finally, there is no way to *find* members by type: the status view
already accepts `?type=` (`MembershipStore.statusView`, matched on
`mt.name`) but the Renewals table exposes only the name search and the
status dropdown. The segment-email compose page has a Type select; the
Renewals page — the natural place to ask "show me the single members" —
does not.

## Approach

Three parts, one CR: instantiate the type, give type change an
interface (and make it possible for the paid-by-fabrication rows), and
surface the existing type filter.

### 1. Instantiate LIFE (migration, no type-management endpoint)

A Flyway migration — next free number; V8 at drafting time, but
CR-016's proposed `sms_opt_out` also targets V8, so whichever lands
second renumbers — that:

- inserts `membership_type` row `LIFE` (description "Life member — no
  annual fee", `minimum_people` 1, `maximum_people` NULL — life
  households include couples, and people-count bounds stay a soft
  guard);
- inserts a `membership_type_price` row of **0 cents for every existing
  period** (`SELECT membership_period_id FROM membership_period`), so a
  retype works in any period and a rollover from any period finds a
  price.

Reference data via migration is the V2 precedent, and it reaches dev,
the demo box and production through the normal deploy with no console
work. The CR-010 decision stands: still no type-management API — a new
type is rare enough that a migration is the interface.

Forward periods need no special handling: the new-period form derives
its price inputs from the union of every period's prices
(`admin.js fillPeriodTypeSelects` — "there is no type API"), so once
LIFE is priced anywhere the form shows a LIFE price input, and period
creation already *requires* a price for every known type
(`PeriodStore`) — the admin types 0. The rollover then does the right
thing forever: it copies the type, reads the target period's price,
and zero-due → ACTIVE immediately (the existing branch).

HONORARY is deliberately out of scope: nobody has asked, and when they
do it is the same mechanism — one more migration.

### 2. Set/unset interface (type change grows a UI; the guard is refined)

**Server.** `changeType` keeps its shape (re-snapshot `amount_due` to
the new type's price in the membership's period, then `recompute`) with
two guard changes:

- The refusal condition changes from "any allocation exists" to
  "**net allocated amount ≠ 0**" (`SUM(amount_cents)` over the
  membership's `payment_allocation` rows). The guard's purpose is that
  nobody reprices under money; a fully-reversed payment leaves no
  money, only history. This is what unlocks
  reverse-then-set-to-LIFE for the imported life members while keeping
  every genuinely-paid membership exactly as protected as today.
- Type change on a CEASED membership is refused (400) — `recompute`
  already never disturbs CEASED, and rewriting the due on a closed
  record has no meaning.

No new endpoint: PUT `/api/admin/memberships/{id}` with
`membershipTypeId` is already the API; "set to LIFE" and "unset" are
the same operation with different targets.

**Semantics worth stating.** Setting LIFE on an unpaid membership:
due becomes $0, recompute flips PENDING_PAYMENT/LAPSED → ACTIVE
(`approved_date` set if never set). Unsetting (LIFE → SINGLE/
HOUSEHOLD): due becomes the real price, recompute flips ACTIVE →
PENDING_PAYMENT since nothing is paid — the household then appears in
the unpaid list and renewal segments, which is exactly what unsetting
means. Life members remain full members everywhere else: MEMBER
relationship keeps voting rights (AGM register includes them), and
they drop out of unpaid-status segments naturally because they are
ACTIVE.

**UI.** The membership manage dialog (Renewals → Manage) gains a Type
row: the current type name, a select of the period's types with prices
("LIFE ($0.00)", "SINGLE ($45.00)", …— from the already-cached period
prices), and a Change button issuing the PUT. A server 400 (net money
on the membership; CEASED) is surfaced verbatim — for a paid membership
it tells the admin to reverse the payment first, which is the CR-003
Reverse button in the same dialog.

### 3. Type filter on the Renewals table

Add a Type dropdown beside the existing status filter, populated like
the email compose page's `emType` (union of period prices), sent as the
`type` query param `renderMemberships` currently omits. Server change:
none — the parameter has existed since CR-003.

### Rejected alternatives

- **A type-management admin page** — rejected again per CR-010: two
  societies' worth of types is not a product surface; a migration per
  new type is honest about how rare this is.
- **A dedicated `set-life` compound endpoint** (reverse + retype in one
  transaction) — the two-step (Reverse, then Change type) uses two
  existing, individually-auditable actions; 8 households do not justify
  a bespoke verb, and the compound would hide a financial correction
  inside a type change.
- **Relaxing the guard to allow retype under net money** (keep due,
  change type only) — repricing and typing must move together or
  `amount_due` stops meaning "the price of this type in this period";
  the net-zero refinement keeps the invariant.
- **A `household.is_life_member` flag** — the type IS the mechanism;
  a parallel flag would fork the pricing/rollover logic the zero-due
  convention exists to avoid.
- **Fixing the 8 by direct SQL** — the register is the financial
  record; corrections go through the app's insert-only discipline
  (reversal + retype), leaving an audit trail an UPDATE would not.

## Remediation runbook (YDHS data)

Run after deploy, **before the live box's 2026-27 rollover** (renewal
opened 1 July; the year starts 1 September 2026):

1. **Confirm the list with the society first.** The spreadsheet is
   ambiguous: the LIFE marker appears in two different columns, and one
   household is marked "Life" yet shows a $65 + $35 payment made in
   2024. The treasurer confirms who is actually a life member; the 8
   flagged rows in the source spreadsheet are the candidate set, not
   the answer.
2. Per confirmed household, in the manage dialog: **Reverse** the
   fabricated "CSV import" payment ($45/$65, method OTHER), then
   **Change type** to LIFE. Status ends ACTIVE with due $0 (transiently
   PENDING_PAYMENT between the two steps — harmless).
3. If the 2026-27 rollover has already run: also change the household's
   2026-27 membership to LIFE (it has no allocations, so this is the
   plain path), which zeroes the due and activates it.
4. Verify with the new type filter: Renewals → Type LIFE lists exactly
   the confirmed households, all ACTIVE, due $0.

## Verification plan

Matrix rows (extend `server/verify-matrix.sh`, self-cleaning fixtures
under `tmp/cr18-fixtures/`):

| # | case | expect |
|---|---|---|
| 1 | guest / user / noaud PUT membership type | 401 / 403 / 401 |
| 2 | migration ran | LIFE present in a period's prices (GET periods shows LIFE $0 in every period) |
| 3 | admin sets an unpaid SINGLE membership to LIFE | 200; due 0, status ACTIVE, approved_date set |
| 4 | admin unsets LIFE → SINGLE on the same membership | 200; due 4500, status PENDING_PAYMENT |
| 5 | admin retypes a membership with a live payment allocation | 400 "cannot change membership type…" |
| 6 | reverse that payment, retype again | 200; ACTIVE, due 0 (the net-zero path) |
| 7 | retype a CEASED membership | 400 |
| 8 | retype with a type that has no price in the period | 400 |
| 9 | rollover a period containing a LIFE membership | new-period membership is LIFE, due 0, ACTIVE; rollover report counts it |
| 10 | create a new period omitting the LIFE price | 400 "missing price for membership type 'LIFE'" |
| 11 | create a new period with LIFE price 0 | 201; LIFE $0 in its prices |
| 12 | status view `?type=LIFE` | only LIFE rows; `?type=SINGLE` unchanged |
| 13 | AGM register export for a period with a LIFE membership | life household's MEMBER people present |
| 14 | financial.csv | LIFE row shows due 0, paid 0 |

Browser walkthrough: manage-dialog type change (set and unset, and the
refusal message on a paid membership); Renewals type filter; new-period
form shows the LIFE price input pre-filled from the base period.

## Results

(to be recorded when implemented)

## Follow-ups / amendments

(dated additions)
