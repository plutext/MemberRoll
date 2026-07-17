# CR 010: Admin "new member" page — person, household and membership in one flow

Status: PROPOSED

Out-of-band CR (like 009): admin UX, no dependency on the 004–008
feature sequence. Builds on CR-009's dialog/person-picker baseline, so
it is sequenced after 009; it can land before or after 004 (if after,
the success screen also offers the CR-004 copy-pay-link).

## Problem

Signing up a walk-in or paper-form member today takes four separate
steps across the register page, in the right order, with ids carried by
hand: create the person, create a household quoting that person's id as
primary contact, open the household to add the partner (creating them
first, in another form), then "New membership" with period and type.
Each step is a form the treasurer must find; forgetting the membership
step leaves a household that never appears in the renewals view. For
the commonest data-entry job in the app — a new member joins — the
register's building blocks are the wrong altitude.

Wanted: one "New member" page — enter the person, the household is
created around them as primary contact, pick the membership type, and
when the type is HOUSEHOLD a dialog collects the second person.

## Approach

### One composite endpoint, one transaction

The wizard collects everything client-side and submits **once**:

| method | path | behaviour |
|---|---|---|
| POST | `/api/admin/new-member` | body below → 201 `{householdId, membershipId, personIds:[...], status, warnings:[...]}` — person(s), household, membership created atomically |

```json
{
  "person":        { …the person-form fields: title, givenName*, familyName*,
                     preferredName, dateOfBirth, emails[], phones[], notes… },
  "householdName": "optional — default '<familyName> household'",
  "membershipPeriodId": 1,
  "membershipTypeId": 2,
  "secondPerson":  { …same person fields…, "relationship": "PARTNER" }
}
```

Server side, in one `Handle` transaction (the CR-003 rule — stores
compose, the resource owns the transaction):

1. insert person 1 (+ contacts);
2. insert the household, primary contact = person 1, plus
   `household_person` rows (person 1 MEMBER, second person with the
   given relationship, default PARTNER; `joined_household_date` =
   today);
3. `MembershipStore.createForHousehold(...)` — **the existing CR-003
   method, unchanged**: snapshots `amount_due_cents` from the period
   price, copies the current household composition into
   `membership_person` (MEMBER/PARTNER → statutory/voting/committee
   true, per the society's both-adults-vote decision), PENDING_PAYMENT
   (ACTIVE when zero-due, e.g. LIFE), and the past-`late_joining_cutoff`
   warning ("consider the next period") passes through to the response.

Any failure rolls the whole thing back — no half-created member, which
is the reason this is a composite endpoint and not the UI replaying the
four existing calls (see rejected alternatives).

Prerequisite refactor: `PersonStore.create` and
`HouseholdStore.create` currently open their own handle
(`jdbi.withHandle`); they gain Handle-taking variants — the same
signature move CR-003 made for the membership/payment writes; existing
endpoints keep their behaviour (the current matrix guards them).

### People-count rules: the `membership_type` columns earn their keep

`membership_type.minimum_people` / `maximum_people` have existed since
CR-001 with nothing reading them. This endpoint is their first
consumer:

- people count > `maximum_people` → **400** (a SINGLE membership with
  a second person is a data error, and the seed data gets SINGLE
  `maximum_people = 1`);
- people count < `minimum_people` → **warning, not error** (a
  HOUSEHOLD membership entered with one person because the partner's
  details aren't to hand is real life; the household detail's
  add-member flow completes it later — note membership_person is NOT
  retro-copied, matching CR-003 rollover semantics, so the warning
  tells the admin to add the partner before creating the membership
  if they want them on the voting register);
- NULL bounds → no check (LIFE, OTHER types unconstrained).

**No migration**: the check reads whatever the columns hold, and NULL
means unchecked. Populating the bounds — SINGLE (1,1), HOUSEHOLD
(2, NULL) — is one psql UPDATE in the verification fixtures for dev
and one line in the production release notes. A data migration that
second-guesses a fielded instance's type rows was considered and
rejected: type rows are operator data (there is deliberately no
type-management API, per CR-003), and a migration overwriting them is
exactly the kind of magic this project avoids.

### The page

`admin/new-member.html` + menu entry (the CR-009 conventions: Pico,
dialogs, person picker not needed here — both people are new). A
short stepper, all client-side until the final submit:

1. **Person** — the person-form fields. On blur of family name /
   email, a courtesy duplicate check via the existing
   `GET /api/admin/people?q=` shows "possible existing matches" with
   links to the register — advisory only (import-style dedup stays
   CR-002/register territory; the server does not reject duplicates,
   same as the existing person form).
2. **Membership** — period (default: the period containing today,
   else latest), type from the period's priced types, price shown;
   household name field prefilled `<familyName> household`.
3. **Second person** — dialog, shown automatically when the chosen
   type's `minimum_people` ≥ 2 (i.e. HOUSEHOLD), offered as a button
   otherwise; same fields plus relationship (default PARTNER).
   Skippable with the under-minimum warning made visible before
   submit.
4. **Create** — one POST; success screen shows what was created and
   deep-links: household detail, membership detail on the renewals
   page (walk-ins often pay cash on the spot — Record payment is one
   click away), and copy-pay-link when CR-004 is present.

### Rejected alternatives

- **Client-side orchestration of the four existing endpoints** — no
  new server code, and a failed step leaves only individually-valid
  rows (people are never deleted anyway)… but a retry after a
  mid-flow failure double-creates people, the membership step can be
  abandoned silently (the exact failure this CR exists to remove),
  and the matrix can't assert atomicity. The stores already compose
  over a Handle; the composite endpoint is a page of code.
- **Folding this into the household form** — the household form
  starts from an existing person (picker); this flow starts from a
  blank person, and mixing "pick or create, then maybe create another"
  into one form is how the current four-step altitude problem started.
- **Waiting for CR-007 (public application form)** — CR-007 is the
  member-facing APPLIED→approval workflow; this is the admin fast
  path for paper forms and walk-ins, needed regardless. CR-007's
  approval step will likely reuse this endpoint's shape (create
  household+membership from an application), noted as a follow-up.
- **Hard-enforcing `minimum_people`** — rejects the partner-details-
  unknown walk-in, forcing the admin back to the four-step flow; a
  warning keeps the fast path fast and the data honest.
- **Dedup rejection on matching name/email** — emails are legitimately
  shared (schema doc: couples), and family names recur; a hard block
  on the commonest surnames in a country town would be worse than the
  duplicate. Advisory matches only.

## Verification plan

Scripted (extend `verify-matrix.sh`; fixtures `tmp/cr010-fixtures/`,
which also set SINGLE (1,1) / HOUSEHOLD (2,NULL) people bounds via
psql). Unique per-run family names as usual.

| # | caller | call | expect |
|---|---|---|---|
| 1 | guest | POST /api/admin/new-member | 403 |
| 2 | testuser | POST /api/admin/new-member | 403 |
| 3 | test-cli-noaud | POST /api/admin/new-member | 401 |
| 4 | testadmin | SINGLE, person only | 201; psql: person + contacts, household (primary contact, default name "<family> household"), household_person MEMBER joined today, membership PENDING_PAYMENT amount_due = period SINGLE price, membership_person statutory/voting true |
| 5 | testadmin | HOUSEHOLD, person + secondPerson (PARTNER) | 201; psql: two people, two household_person rows, ONE membership, both membership_person rows voting true |
| 6 | testadmin | SINGLE **with** secondPerson | 400 (maximum_people); psql: nothing created |
| 7 | testadmin | HOUSEHOLD, person only | 201 + minimum_people warning |
| 8 | testadmin | secondPerson relationship DEPENDANT | 201; second membership_person voting false |
| 9 | testadmin | missing familyName on secondPerson | 400; psql: **no rows created at all** (atomicity — person 1 rolled back too) |
| 10 | testadmin | unknown membershipTypeId / periodId | 400 naming the field |
| 11 | testadmin | type with no price for the period (fixture LIFE-less period) | 400 |
| 12 | testadmin | zero-due type (LIFE) | 201, status ACTIVE, approved_date today, no payment row |
| 13 | testadmin | period whose late_joining_cutoff < today | 201 + cutoff warning |
| 14 | testadmin | explicit householdName | 201, name used verbatim |
| 15 | testadmin | re-run of #4's exact body | 201, second person/household created (no dedup rejection — advisory only, by design) |

Regression: full existing matrix stays green (the store refactor
touches CR-001 code paths).

Browser walkthrough:

1. New member (menu) → SINGLE: enter person, see period/type prices,
   create → success screen; renewals view shows the household Unpaid;
   Record payment from the deep link works.
2. HOUSEHOLD: second-person dialog appears automatically; fill both,
   create → household detail shows both members, membership shows
   both names.
3. HOUSEHOLD, skip second person → the warning is visible before and
   after submit; add the partner later via household detail.
4. Duplicate courtesy check: enter a family name that exists →
   advisory matches appear with register links; proceeding still
   works.
5. SINGLE with a second person added then type switched to SINGLE →
   the wizard surfaces the conflict before submit (client mirrors the
   server rule).

## Results

(to be filled in after implementation)

## Follow-ups / amendments

- CR-007's approval step should reuse this endpoint's creation shape
  (application → household + membership) rather than growing a second
  composite path.
- Production release note: set SINGLE (1,1) / HOUSEHOLD (2,NULL)
  people bounds via psql (deliberately not a migration — see design).
