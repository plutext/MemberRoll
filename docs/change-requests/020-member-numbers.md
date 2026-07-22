# CR 020: Member numbers — decouple the card's number from person_id

Status: IMPLEMENTED + VERIFIED (2026-07-23)

## Problem

The committee has asked that life members hold the lowest member numbers
on their CR-017 membership cards (request received 2026-07-23).

The card prints `"Member no. " + person_id` (`Cards.memberNoText`) — the
member number IS the database surrogate key. That coupling makes the
request unserviceable in any durable way:

- `person_id` is `GENERATED ALWAYS AS IDENTITY`: the application can
  never assign one (no `OVERRIDING SYSTEM VALUE` anywhere in the app),
  and Postgres refuses a plain `UPDATE` of the column outright. Ids are
  allocated in creation order, forever.
- Life membership is an honour usually conferred late in a membership.
  Whatever we do to today's eight-or-so life members, the **next** life
  member — awarded at some future AGM — already holds whatever id they
  got on the day they joined. Any id-based scheme (including "reserve
  the first 30 ids") is one-shot: it works on import day and never
  again without hand-run SQL surgery.
- The same coupling blocks every adjacent request we should expect from
  a 60-year-old historical society: legacy member numbers from paper
  records, "the president is member no. 1", a couple wanting consecutive
  numbers.

Two remediation-by-renumbering routes were considered with Jason on
2026-07-23 and rejected (recorded here because the wipe window closes at
go-live and someone will ask again later):

- **Wipe the demo box and re-import a CSV sorted life-members-first,
  reserving ids 1–30.** Available only pre-go-live; loses everything
  accrued since import (recorded payments, Keycloak links — though
  re-provisioning heals those — committee register, settings, pay
  links); needs the treasurer's still-pending confirmed life list; and
  the reservation is dead weight afterwards (see above). It also
  trips an importer defect, fixed by this CR (part 3): the importer
  predates CR-018 and mishandles zero-due types.
- **Renumber `person_id` in place with SQL.** Requires dropping and
  re-adding the identity, rewriting every referencing column
  (`household.primary_contact_person_id`, `household_person`,
  `membership_person`, `email_address`, `phone_number`,
  `communication_preference`, `payment.payer_person_id`,
  `committee_appointment`) in one transaction, and resetting the
  sequence — hand surgery on the financial record with no audit trail,
  where one missed FK corrupts silently.

The root cause is a presentation/identity concern riding a surrogate
key. Meaningful numbers need an attribute.

## Approach

Three parts, one CR: the column, the surfaces that read and write it,
and the importer's zero-due fix (found while assessing the re-import
route; folded in because it is small, related, and worth having whether
or not anyone re-imports).

### 1. `person.member_no` (V9)

A Flyway migration — next free number, V9 at drafting time (CR-016's
proposed `sms_opt_out` targeted V8 and lost the race to CR-018;
whichever of CR-016/CR-020 lands second renumbers):

```sql
ALTER TABLE person ADD COLUMN member_no integer
    CHECK (member_no IS NULL OR member_no > 0);
CREATE UNIQUE INDEX person_member_no ON person (member_no)
    WHERE member_no IS NOT NULL;
```

Nullable, **no backfill**: an unassigned person's card keeps showing
their `person_id`, exactly as today (the display derivation below), so
nothing changes for anyone until a number is deliberately assigned.
Uniqueness is a partial index — the null majority never collides.

### 2. Surfaces

**Card (the one renderer — the CR-012/CR-017 lesson).** `Cards.compose`
selects `COALESCE(p.member_no, p.person_id) AS member_no`; the `Card`
record gains a `memberNo` field which `memberNoText()` and `toJson`
(`"memberNo"`) use. Every card surface — member page, admin dialog,
download, print, email attachment, `card/info` JSON — inherits from
that one spot. `personId` stays on the record (it is the compose key,
not the display number).

**Admin person form.** A "Member no." input beside the existing person
fields (`admin/index.html` person dialog): blank = unassigned. Rides
the existing person JSON (`memberNo`, integer) through POST/PUT/GET on
`AdminPeopleResource`/`PersonStore` with the form's wholesale-replace
semantics (an absent/blank `memberNo` on PUT clears it, like emails and
phones). A duplicate number is a **409** naming the number (unique-index
violation mapped to `ConflictException`); zero/negative is a **400**
(the CHECK's belt-and-braces). No auto-assignment — the admin types the
number; this society has ~30 candidates, and "next free" logic would
just fight legacy paper numbers.

**Policy, not mechanism, for the reservation.** "1–30 are for life
members" is a user-manual sentence, not a constraint — the app enforces
uniqueness only. A range rule would be dead weight (and wrong the day a
legacy paper number outside the range shows up).

### 3. Importer zero-due fix

`ImportService.applyWrites` predates CR-018's LIFE type and mishandles
any zero-due group:

- `paid=yes` fabricates a $0 import payment, violating the
  `payment.amount_cents <> 0` CHECK — the whole import transaction
  rolls back;
- `paid=no` inserts the membership as PENDING_PAYMENT with $0 due
  (the zero-due→ACTIVE branch lives in `createForHousehold`, which the
  import bypasses via raw `insertMembership`).

Fix, pinned to the CR-001 convention: a group whose `amountDueCents`
is 0 imports as **ACTIVE (approved = import date) with no payment row,
regardless of the `paid` flag**, and the preview's payment count
excludes zero-due groups. Non-zero-due behaviour stays byte-for-byte
(the existing CR2-* matrix rows are the guard).

### Rejected alternatives

- **Renumbering `person_id`** (either route) — see Problem; recorded as
  rejected so the wipe-window question doesn't reopen.
- **Backfilling `member_no = person_id` for everyone** — churn with no
  behaviour change; `COALESCE` at read time is the same thing without
  30 UPDATE statements and a second source of truth to keep aligned.
- **Range enforcement for the life-member block** — policy in the
  manual; a constraint would reject legitimate legacy numbers.
- **Auto-assignment ("next free number")** — fights legacy numbering,
  and the admin assigning ~30 numbers by hand is the entire workload.
- **`member_no` in the CR-019 register-of-members export** — clause 4
  does not ask for a number; add it later if the committee wants it
  (one column in `ReportStore.registerOfMembers`).
- **Showing member no. in the Renewals/people tables** — no stated
  need; the card is the surface the request is about.

## Remediation runbook (YDHS data)

After deploy, once the treasurer confirms the life-member list (the
same confirmation CR-018's runbook step 1 is waiting on):

1. Complete the CR-018 runbook first (reverse + retype to LIFE), if not
   already done.
2. Register → People → open each confirmed life member → set
   **Member no.** 1–30 (the committee chooses the ordering — e.g. by
   date the honour was conferred).
3. Re-issue cards from the admin membership dialog (Card…) — the card
   is composed live, so the new number appears on the next render; any
   previously downloaded/printed card is stale but self-evidently so
   (CR-017's staleness position).

## Verification plan

Matrix rows (extend `server/verify-matrix.sh`, self-cleaning fixtures,
unique names):

| # | case | expect |
|---|---|---|
| 1 | PUT person with `memberNo: 7` | 200; GET echoes 7 |
| 2 | card info for that person (ACTIVE membership fixture) | `memberNo` 7; PNG still renders (magic bytes) |
| 3 | card for a person with no member_no | `memberNo` = person_id (CR17-02f keeps passing — the regression guard) |
| 4 | PUT another person with `memberNo: 7` | 409 naming the number |
| 5 | PUT `memberNo: 0` / negative | 400 |
| 6 | PUT the person again without `memberNo` | 200; cleared (GET null); card falls back to person_id |
| 7 | import a LIFE (zero-due) group with `paid=yes` | 201 import; membership ACTIVE, approved set, NO payment row |
| 8 | import a zero-due group with `paid=no` | ACTIVE all the same |
| 9 | import preview of a zero-due `paid=yes` group | payment count excludes it |
| 10 | existing paid import rows (CR2-*) | unchanged/green |

Browser walkthrough: person form round-trips a member number (set,
duplicate refused visibly, clear); the admin card dialog and the member
page card both show the assigned number; a card for an unassigned
person still shows the id.

## Results

Implemented 2026-07-23, exactly as proposed:

- **V9** `person.member_no` (nullable + CHECK > 0, partial unique index
  `person_member_no`) — applied cleanly by Flyway against the existing
  dev database on webapp start.
- **PersonStore**: `Person` record gained `Integer memberNo`; create and
  update bind it, and the one anticipated write failure — the unique
  index tripping — is mapped to `ConflictException` naming the number
  (`memberNoConflict`); anything else re-throws untouched.
- **AdminPeopleResource**: `memberNo` rides the person payload (absent/
  null on PUT clears — the form's wholesale-replace semantics); zero,
  negative or non-numeric is a 400; the store's conflict surfaces as a
  409 on both POST and PUT. `parseObject` is shared with CR-010's
  new-member endpoint, which already mapped ConflictException, so the
  field composes there for free.
- **Cards**: `compose` selects `COALESCE(p.member_no, p.person_id) AS
  member_no`; the `Card` record's new `memberNo` feeds `memberNoText()`
  and `toJson` — every surface (member page, admin dialog, download,
  print, email attachment, both `card/info`s) inherited it from that one
  spot with no per-surface change. `personId` stays the compose key.
- **Importer**: a zero-due group imports ACTIVE (approved = import date)
  with no payment row regardless of `paid`; the preview's payment count
  excludes it.
- **UI**: "Member no." input in the person dialog (`pfMemberNo`), blank
  = unassigned; payload sends `memberNo` only when set.

### Matrix

23 new CR20-* rows (assign/echo, card shows it, PNG renders, duplicate
409 naming the number on PUT and POST, 0/negative/non-numeric 400s,
clear-on-absent + card fallback, zero-due import ACTIVE/no-payment/
preview-count with psql side-effect checks). Self-cleaning: the number
is unique per run AND cleared by the row-6 check. CR17-02f (card
memberNo = person id for an unassigned person) stayed green as the
regression guard, as did the CR2-* non-zero-due import rows.

Full matrix run twice on the dev stack, 2026-07-23: **PASS=743 FAIL=3**
both runs, the 3 being the pre-existing environmental flakes (Keycloak
listing row 27b, and the two "today" rows CR10-04g2/CR10-12c — the
Postgres container's UTC `current_date` was still 07-22 during the
morning-AEST run), unrelated to this CR.

### Browser walkthrough

`tmp/cr020-fixtures/cr020-walkthrough.js` (Playwright), **7/7**: person
form round-trips the number; a duplicate is refused visibly in the open
dialog (409 naming the number) and clearing the field then saves; the
admin Card… dialog renders the PNG with the assigned number; the member
page's own card shows an assigned number and falls back to the person id
when cleared. Screenshots confirm the number on both card surfaces.

## Follow-ups / amendments

(dated additions)
