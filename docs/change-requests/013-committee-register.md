# CR 013: Committee register — office-bearers, terms, and the AGM roll

Status: PROPOSED (2026-07-19)

## Problem

The app records members but has no notion of the **committee** that runs
the society. Two concrete needs surface it:

1. **CR-007 needs it.** Approval of an application is a committee
   decision, not an admin click (clause 3(3)–(4): the secretary refers
   the application to the committee, which must approve or reject). To
   *notify* the committee — or route the referral to the secretary — the
   app must know who they are. Today it can't.
2. **It's a statutory record.** The association keeps a record of its
   committee (the constitution defines the committee's composition,
   terms and offices — clauses 14–19; the Associations Incorporation Act
   requires office-holders be recorded and committee changes notified to
   Fair Trading). That record currently lives only on paper. This is a
   governance register — a sibling of CR-011's register-of-members work,
   not merely a CR-007 convenience.

What exists nearby but is **not** this: `membership_person.
eligible_for_committee` marks a statutory MEMBER as *eligible to stand*
for committee. Being *on* the committee is a separate fact, recorded
nowhere.

The constitution fixes the shape (verified against tmp/Constitution.pdf,
updated 6/2024):

- **Composition (cl. 14):** office-bearers are president, vice-president,
  secretary, treasurer; plus at least one ordinary committee member.
  One person **may hold up to two offices** (cl. 14(2)) — except not
  both president and vice-president.
- **Election (cl. 15):** office-bearers and ordinary members are elected
  at the AGM, office by office then the ordinary members.
- **Terms (cl. 16):** a committee member "holds office from the day the
  member is elected until immediately before the next annual general
  meeting"; is eligible for re-election (16(2)); no term limit (16(3)).
- **Casual vacancy (cl. 17):** a mid-year appointment holds office only
  until the next AGM.

So a committee position is a **term-bounded appointment of a person to an
office**, AGM to AGM — exactly the temporal shape the register already
uses for `household_person` (a current row has a null end date; leaving
and returning is two rows).

## Approach

### The record — `committee_appointment` (V7 migration)

One append-of-terms table, modelled on `household_person`'s
current-is-null idiom:

```sql
CREATE TABLE committee_appointment (
    committee_appointment_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    person_id     bigint NOT NULL REFERENCES person ON DELETE RESTRICT,
    office        text   NOT NULL
                  CHECK (office IN ('PRESIDENT','VICE_PRESIDENT','SECRETARY','TREASURER','ORDINARY')),
    started_date  date   NOT NULL,   -- the AGM (or casual-vacancy date) the term begins
    ended_date    date,              -- NULL = currently serving; set to the next AGM date
    elected_date  date,              -- when elected/appointed (usually = started_date)
    minute_ref    text,              -- optional pointer into the society's minutes
    notes         text,
    recorded_by   text   NOT NULL,
    recorded_at   timestamptz NOT NULL DEFAULT now(),
    CHECK (ended_date IS NULL OR ended_date >= started_date)
);
-- one person holds a given office at most once concurrently
CREATE UNIQUE INDEX committee_appointment_current
    ON committee_appointment (person_id, office) WHERE ended_date IS NULL;
-- the singular offices have at most one current holder (ordinary seats are many)
CREATE UNIQUE INDEX committee_appointment_singular_office
    ON committee_appointment (office)
    WHERE ended_date IS NULL AND office <> 'ORDINARY';
CREATE INDEX committee_appointment_person ON committee_appointment (person_id);
```

Notes on the shape:

- **Keyed on `person_id`, not `membership_person`.** An appointment
  spans years and periods; the same person sits across many membership
  rows. The link to "is a current statutory member" is checked at the
  application layer (a soft guard, warned not blocked — the committee,
  not the app, is the authority on eligibility), like CR-006's
  one-email-per-household rule.
- **`office` is a per-term fact.** A person can be treasurer one year,
  president the next; each term is its own row carrying its own office,
  so that history falls out for free — nothing is mutated.
- **Two offices, one person (cl. 14(2))** is naturally two open rows with
  different `office` values; `committee_appointment_current` forbids only
  a *duplicate* office for the same person. The pres/vice-pres exclusion
  is an application-layer check (a two-row cross-condition a single index
  can't express).
- **Singular offices have one holder** via the partial unique index;
  ordinary seats are unbounded.
- **Current committee = `ended_date IS NULL`.** History is preserved
  ("who was president in 2024").

### The AGM roll — close-all-then-open-new

Because every seat is vacated and re-filled at the AGM (cl. 15–16), the
primary flow mirrors the membership rollover (`MembershipStore.
rolloverApply`): the admin records the newly-elected committee, and in
**one transaction** the endpoint ends every currently-open appointment
(`ended_date = agmDate`) and inserts the new ones (`started_date =
elected_date = agmDate`). Same atomic discipline as CR-010: every
rejection is a thrown `IllegalArgumentException`/`ConflictException`
inside the transaction lambda — never an early-return `Response` — so a
bad line rolls the whole roll back rather than leaving the committee
half-replaced.

Rejections the roll enforces before writing: an office listed twice
(other than ORDINARY); a person given both president and vice-president;
an unknown person id. A missing office (e.g. no treasurer elected yet) is
allowed with a warning in the response, not blocked — the constitution
expects the offices filled but the register must be able to represent an
interim gap.

### Mid-year changes (casual vacancy, resignation — cl. 17)

- **Resignation / vacancy:** close one appointment (`PUT` sets
  `ended_date`); the seat is simply empty until filled.
- **Fill a casual vacancy:** open one appointment (`POST`) with
  `started_date`/`elected_date` = the appointment date; it will be closed
  at the next AGM roll like any other.

### Endpoints (all `@RolesAllowed("admin")`, `AdminCommitteeResource`)

| method | path | behaviour |
|---|---|---|
| GET | `/api/admin/committee` | the current committee (`ended_date IS NULL`), ordered president → vice-president → secretary → treasurer → ordinary (by name); each row carries person id/name, office, started/elected date, minute ref. `?includeEnded=true` returns full history (newest term first). |
| POST | `/api/admin/committee/agm` | the AGM roll. Body `{agmDate, minuteRef?, appointments:[{personId, office, notes?}]}`. Closes all open appointments at `agmDate`, inserts the new term. 400 on a duplicate singular office, a person holding pres+vice-pres, or an unknown person. Returns the new current committee + any `warnings` (unfilled office, a non-member appointee). |
| POST | `/api/admin/committee/appointments` | open one appointment (casual vacancy / correction of an omission). Body `{personId, office, startedDate, electedDate?, minuteRef?, notes?}`. 409 if that person already holds that office, or the singular office is taken. |
| PUT | `/api/admin/committee/appointments/{id}` | correct an appointment in place — office, dates, minute ref (see "corrections" below). Closing a term is `{endedDate}`. 404 unknown. |
| DELETE | `/api/admin/committee/appointments/{id}` | remove a mistaken row entirely. 404 unknown. |

**Corrections are edits, not reversals** — deliberately unlike payments.
The committee register is administrative reference data, not a financial
ledger; a mis-typed office is fixed in place (`PUT`) or a bogus row
removed (`DELETE`), with `recorded_by`/`recorded_at` carrying the audit
trail. There is no "negative appointment". The *term history* that
matters (who held what, from when to when) comes from real
started/ended dates, not from preserving typos.

### UI (admin, new "Committee" section)

A page beside Users/Import (the menu-driven admin split from CR-009):

- **Current committee** — a table by office, each row with the member's
  name, office, since-date; an "End term…" action (resignation) and an
  "Add appointment…" dialog (casual vacancy), both over the single-
  appointment endpoints. Members are chosen with the CR-009 person
  picker (`GET /api/admin/people?q=`).
- **Record AGM committee** — a dialog to enter the AGM date + minute ref
  and the elected slate (office selects + person pickers, "add ordinary
  member" repeats), posting the roll; the response's warnings surface
  under the same in-dialog `say()` banner used elsewhere.
- **History** — a collapsed view of past terms (`?includeEnded=true`).

### Relationship to Keycloak — no role yet

Committee-ness is a **register/governance fact**, so it lives in Postgres
keyed by `person_id` — never in Keycloak. Keycloak roles in this app
exist to gate *app surfaces* (`member` sees own membership, `admin` the
panel, `manager` is grant-only); a `committee` role earns its keep only
when there is a surface *only committee members may use*. There is none:
CR-007 approval is the **secretary recording the committee's decision**,
for which the committee needs an email address (this register), not an
account.

If such a surface later exists (each member individually approving an
application online, a committee-only dashboard), a `committee` role is
**derived from this register** at provision/reconcile time — the CR-006
pattern where the register is authority and the role is only UX — via
`person.keycloak_subject`. Minting the role now, with nothing behind it,
would be a second source of truth to drift. Explicitly out of scope here.

### CR-007 hand-off (the seam, not the build)

This CR exposes what CR-007 will consume; it does not build the approval
flow. A store read — current committee members' delivery addresses
(person primary email), and the current secretary specifically — is the
join CR-007's referral/notification email uses (via the existing `Mail`
addressing, the CR-005 machinery not required for one transactional
notice). CR-007's approve/reject action already carries a decision date
and optional minute reference (its pre-proposal notes, principle 1);
those are the application's, and are unrelated to this table.

### Rejected alternatives

- **A single row per person with a bumped `reelected_date`** — loses
  multi-term history and can't represent an office change between terms.
  Append-of-terms matches cl. 16 (a fresh term each AGM) and the
  `household_person` idiom.
- **A `committee` boolean on `person`** — can't carry office, term dates,
  or history; a committee is inherently temporal.
- **A Keycloak `committee` role now** — no app surface to gate; would be
  dead weight and a drift risk (see above).
- **Append-only with negative/reversal corrections (the payment rule)** —
  over-applies a financial-ledger discipline to administrative reference
  data; edit-in-place is simpler and loses nothing that matters.
- **Modelling nominations/ballots** — the register records the *outcome*
  (who holds what, from when), not the election mechanics; a candidate
  who loses a nomination is not a register fact.

## Config

None. No new env, no realm change. One new migration (V7), no change to
existing tables.

## Verification plan

New `CR13-*` rows in `server/verify-matrix.sh` against committee
fixtures (a person A elected president, B secretary, C+D ordinary).

| # | caller / call | expect |
|---|---|---|
| 1 | guest / user / noaud → GET `/api/admin/committee` | 403 / 403 / 401 |
| 2 | guest / user → POST `/api/admin/committee/agm` | 403 / 403 |
| 3 | admin → POST `.../agm` with {A:president, B:secretary, C:ordinary, D:ordinary} | 201; GET current shows 4, ordered pres→sec→ordinary |
| 4 | admin → GET `/api/admin/committee` | A office PRESIDENT, `since` = the AGM date, `ended` null |
| 5 | admin → POST `.../agm` naming two presidents | 400, nothing written (prior committee intact) |
| 6 | admin → POST `.../agm` giving A both president and vice-president | 400 |
| 7 | admin → second `.../agm` (next AGM) with a new slate | prior 4 all carry `ended_date` = new AGM date; new rows current; history GET shows both terms |
| 8 | admin → POST `.../appointments` {A, TREASURER} while A is president | 201 (cl. 14(2): one person, two offices) |
| 9 | admin → POST `.../appointments` a second SECRETARY (B already secretary) | 409 (singular office taken) |
| 10 | admin → PUT `.../appointments/{id}` {endedDate} (resignation) | 200; A absent from current, present in history |
| 11 | admin → POST `.../appointments` a person with no current membership | 201 with a `warnings` entry (soft guard, not blocked) |
| 12 | admin → DELETE `.../appointments/{id}` for a mistaken row | 200; gone from current and history |
| 13 | psql | `committee_appointment_singular_office` present; a hand-inserted second open president is rejected by the index |
| 14 | admin → GET current committee emails helper (CR-007 seam) | returns B's (secretary) primary email for the routing case |

Browser walkthrough: the Committee section — record an AGM slate, see the
current table populate; add a casual-vacancy appointment via the person
picker; end a term; open the history view.

## Results

(to be recorded when implemented)

## Close-out (on implementation)

- `docs/membership_management_database_schema.md`: add the
  `committee_appointment` table and a revision-history entry; cross-
  reference `eligible_for_committee` ("eligible to stand" vs. this
  table's "is serving").
- `CLAUDE.md`: an architecture paragraph (the append-of-terms model, the
  AGM-roll atomic pattern, the deliberately-deferred Keycloak role).
- `docs/ROADMAP.md`: add the CR-013 row; note the CR-007 dependency
  (CR-007's notification step consumes this register).

## Follow-ups / amendments

- If Fair Trading change-notification is ever automated, the committee
  history here is the source; out of scope for v1 (manual lodgement).
- A `committee` Keycloak role, derived from this register, if and when an
  in-app committee-only surface exists (see "Relationship to Keycloak").
