# CR 002: Member list import (CSV, preview + apply)

Status: VERIFIED

## Problem

The society's member register currently lives in a spreadsheet. CR-001
built the database register; nobody is going to hand-enter ~100 people
through the admin forms, and other societies adopting the app will have
the same first-day problem. Import needs to be a real, re-runnable
feature with a review step — not a one-off script — because the first
attempt will surface data-quality surprises (shared emails, ambiguous
households, typos) that the treasurer must see *before* anything is
written.

Roadmap scope (CR-002): CSV import with a preview/dedup step, plus a
synthetic dev fixture shaped like the real list.

## Approach

### What gets imported

A row describes a **person**; rows sharing a `household` label group
into one **household**. The import creates, per the CR-001 schema:

- `person` rows with `email_address` / `phone_number` children;
- `household` + `household_person` rows (first row of each household
  group is the primary contact; joined date = import date);
- one postal `household_address` per household (from its first row);
- optionally, one `membership` per household for a named
  `membership_period`, typed via `membershipType`, with
  `membership_person` rows for every person in the household —
  DEPENDANT rows get statutory/voting/committee flags **false**,
  MEMBER/PARTNER rows true (the society's both-adults-vote decision);
- for rows marked `paid`, one `payment` (+ MEMBERSHIP
  `payment_allocation` for the full `amount_due_cents`).

**Why memberships and payments are in scope**: the spreadsheet this
replaces is fundamentally a record of *who is financial*. Importing
people without memberships would leave the register unable to answer
its first question until CR-003, and importing `ACTIVE` memberships
without payment rows would violate schema rule 6 (paid-ness derives
from `payment_allocation`) — CR-003's unpaid-list would immediately
contradict the imported reality. So paid rows get a real payment row:
`payment_method` OTHER, `received_date` = import date, `recorded_by` =
the importing admin, notes "CSV import". Unpaid rows produce a
PENDING_PAYMENT membership. `amount_due_cents` comes from
`membership_type_price` for the target period.

### Canonical CSV, not column mapping

The server accepts **one canonical column set** (below); the treasurer
massages their spreadsheet into it once (a column-rename exercise in
Excel, saved as "CSV UTF-8"). A general column-mapping UI was rejected:
it is a large feature serving exactly one import per society, and the
mapping still has to be reviewed by a human — that review IS the
massage step. `docs/import-template.csv` ships as the fillable example.

Columns (header row required; order free; unknown columns → error, so
typos don't silently drop data):

| column | required | notes |
|---|---|---|
| `household` | no | grouping label, e.g. "Smith". Blank → one-person household auto-named "<Given> <Family>" |
| `title`, `givenName`*, `familyName`*, `preferredName` | given+family | |
| `relationship` | no | MEMBER / PARTNER / DEPENDANT / OTHER; default MEMBER |
| `email` | no | multiple separated by `;`, first is primary; lowercased on import |
| `phone`, `phoneType` | no | one number; type MOBILE / HOME / WORK, default blank |
| `line1`, `line2`, `locality`, `state`, `postcode` | no | postal address; read from the household's first row |
| `membershipType` | no | SINGLE / HOUSEHOLD (must exist in `membership_type`); blank → no membership created for this household |
| `paid` | no | `yes`/`no` (also y/true/1); blank = no |
| `notes` | no | → `person.notes` |

Per-household consistency rules (violations are errors): every row of a
group must agree on `membershipType` and `paid` where set; SINGLE with
more than one person, or HOUSEHOLD with one, is a **warning** (the
society may have odd legacy cases — the human decides).

### Two-phase flow: preview, then apply

Both admin-only (`@RolesAllowed("admin")`), CSV as the request body
(`Content-Type: text/csv`; the admin page reads the file with
`FileReader` and sends the text — no multipart machinery).

| method | path | behaviour |
|---|---|---|
| POST | `/api/admin/import/preview` | parse + validate + dedup; returns the report; **writes nothing** |
| POST | `/api/admin/import?period=2026-2027` | same validation; if any errors → 400 with the report; else applies in **one transaction** and returns the report with created counts |

`period` names the target `membership_period` (also accepted on
preview; default = the period whose date range contains today). Rows
with no `membershipType` import people/households only, so the feature
still works before any period/pricing exists.

**Report shape** (same for both endpoints):

```json
{
  "rows": 97,
  "errors":   [{"line": 12, "message": "givenName is required"}],
  "warnings": [{"line": 30, "message": "email jo@x.com already belongs to person #14 (Jo Smith)"}],
  "skipped":  [{"line": 30, "reason": "existing person matched"}],
  "toCreate": {"people": 90, "households": 61, "memberships": 58, "payments": 40},
  "created":  {"people": 90, "households": 61, "memberships": 58, "payments": 40}
}
```

(`created` only on apply.)

### Dedup and re-runnability

A row **matches an existing person** when any of its emails equals an
existing `email_address` (case-insensitive) OR given+family name match
case-insensitively. Matching rows are **skipped on apply** (reported in
`skipped`, warned in preview) — so re-running the same file after a
partial cleanup is safe and converges instead of duplicating. A
household whose every person is skipped is itself skipped. Name-only
matches are conservative (two real "John Smith"s would need a manual
entry for the second) — acceptable at this scale, and the warning says
which existing person matched so the human can judge.

Within-file checks: the same email in two different household groups is
a warning (couples legitimately share an email *within* one household);
duplicate given+family within the file is a warning.

One membership per household per period is enforced by the CR-001
unique constraint; the import checks first and warns/skips rather than
aborting the transaction.

### Parsing

`org.apache.commons:commons-csv` (new dependency — earns its keep:
RFC 4180 quoting, embedded newlines, and Excel's UTF-8 BOM are a
classic hand-rolled-parser bug farm; the jar is small and has no
transitive dependencies). Import is synchronous — ~100 rows, one
request, no job machinery. Hard cap: 1 MB body / 2000 rows (this is a
society register, not an ETL pipeline).

### Store and UI

`ImportService` (package-private, takes `Jdbi`): pure-function parse +
validate returning the report model, and an apply that runs inside
`jdbi.inTransaction` reusing `PersonStore`/`HouseholdStore` where the
shapes fit and direct SQL where they don't (membership/payment inserts
belong to CR-003's stores later; the import writes them directly for
now and CR-003 refactors if worthwhile).

Admin page: an **Import** block in the Register section — file input,
Preview button rendering the report (counts + errors/warnings/skipped
tables), and an Apply button enabled only when the preview had zero
errors. Same classic-script style.

### Rejected alternatives

- **Column-mapping UI** — above; the porting door for other societies
  is the documented template.
- **XLSX parsing** (Apache POI) — heavy dependency to avoid one
  save-as-CSV step; Excel's "CSV UTF-8" export is lossless for this
  data. Doc must say *UTF-8* explicitly — plain "CSV" from Excel is
  windows-1252 and mangles accented names.
- **Upsert/merge of existing people** — v1 creates only; corrections
  flow through the admin UI. Merge semantics (which field wins?) are a
  swamp not needed for a first import into an empty register.
- **Client-side CSV parsing** — server must be authoritative anyway
  (scripted imports, other societies' tooling); one parser, not two.
- **Multipart upload** — `text/csv` body is simpler on both ends.

## Verification plan

Scripted (extend `server/verify-matrix.sh`; fixture CSVs written by the
script itself into `tmp/cr002-fixtures/`). Unique per-run family names
keep re-runs green, as in CR-001.

| # | caller | call | expect |
|---|---|---|---|
| 1 | guest | POST /api/admin/import/preview | 403 |
| 2 | testuser | POST /api/admin/import/preview | 403 |
| 3 | testadmin | preview, valid 5-row/2-household CSV (couple + single + dependant) | 200; rows=5, errors=[], toCreate {people:5, households:2, memberships:2, payments:1} |
| 4 | testadmin | preview, missing givenName on a row | 200; errors has line number; toCreate excludes that household |
| 5 | testadmin | preview, unknown column `surname` | 400 or errors entry naming the column |
| 6 | testadmin | preview, row whose email matches a person created earlier in the matrix | 200; warning + skipped entry naming the existing person id |
| 7 | testadmin | apply, CSV from #4 (has errors) | 400, nothing created (counts via people search unchanged) |
| 8 | testadmin | apply, valid CSV, period=2026-2027 | 200; created == toCreate |
| 9 | testadmin | GET /api/admin/households?q=<imported name> | household present, correct member count, primary contact = first row |
| 10 | testadmin | GET /api/admin/people?q=<imported family> | people present; emails lowercased; phone attached |
| 11 | testadmin | re-apply the same CSV | 200; created {0,0,0,0}; every row in skipped |
| 12 | testadmin | apply CSV with `paid=yes` household | psql: membership ACTIVE, amount_due 6500; payment amount 6500 method OTHER recorded_by=testadmin; allocation MEMBERSHIP links them |
| 13 | testadmin | apply CSV with `paid=no` household | psql: membership PENDING_PAYMENT; no payment row |
| 14 | testadmin | apply CSV with blank membershipType | people/household created; no membership row |
| 15 | testadmin | preview, DEPENDANT row | after apply: membership_person flags false for the dependant, true for MEMBER/PARTNER (psql) |
| 16 | testadmin | CSV with quoted field containing comma + UTF-8 BOM prefix | parsed correctly (name intact in GET) |
| 17 | testadmin | body > 1 MB | 413 |

Browser walkthrough: Register → Import → choose the synthetic fixture
file → Preview shows counts and a deliberate warning → Apply → people
and households visible in the lists; re-Apply shows all-skipped.

Existing matrix must stay green. No compose/auth/Caddy changes → no
deploy Local smoke needed.

Synthetic fixture: `tmp/cr002-fixtures/synthetic-members.csv`, ~20 rows
shaped like the society's list (couples sharing an email, a dependant,
a single member, an unpaid household, accented names) — plus the
committed `docs/import-template.csv` with header + 3 example rows.

## Results

**2026-07-17**, dev machine, on the project's 18xxx ports (Tomcat 18080,
Keycloak 18081, Postgres 5433).

**Delivered:** `commons-csv` 1.11.0 dependency (no runtime transitives);
`ImportService` (parse → validate/dedup → apply, sharing one validation
pass between the two phases); `AdminImportResource` (`POST
/api/admin/import/preview` and `POST /api/admin/import?period=`, both
`@RolesAllowed("admin")`, `text/csv` body, 1 MB cap → 413); registered in
`ApiApplication`; the Import block in the admin Register section
(`admin/index.html` + `admin.js`, file input → Preview → gated Apply,
rendering the counts/errors/warnings/skipped report); and the committed
`docs/import-template.csv` (header + three example rows).

**HTTP + side-effect matrix** (`PORT=18080 KEYCLOAK_PORT=18081
POSTGRES_PORT=5433 server/verify-matrix.sh`): **PASS=130 FAIL=0** — the
83 pre-existing CR-001 checks plus 47 new checks (60–79) covering plan
cases 1–17. Ran twice consecutively, green both times, confirming
re-runnability (each run uses a unique per-run family name `Imp$$`, and a
re-apply of the same file matches every person on dedup and creates
nothing). Highlights:

- role gates: guest 403, member 403 on preview (case 1–2);
- preview of the valid 5-row / 2-household fixture (a couple + a
  dependant, paid, in one household; a couple, unpaid, in the other):
  `rows=5`, `errors=[]`, `toCreate {people:5, households:2,
  memberships:2, payments:1}` (case 3);
- a blank `givenName` errors that row and drops its whole household from
  `toCreate` (case 4); an unknown column `surname` is reported by name
  (case 5);
- apply of the errored file → **400**, and a people search confirms it
  wrote nothing (case 7); apply of the valid file → **200** with
  `created == toCreate` (case 8);
- `GET /admin/households` shows the imported household with 3 current
  members (case 9); `GET /admin/people` shows the imported people with a
  mixed-case email stored lowercased (`Aaron.PID@Example.COM` →
  `aaron.pid@example.com`) and the phone attached (case 10);
- a row whose email collides with an imported person is **warned** and
  **skipped** naming the existing person id, creating nobody (case 6);
  re-applying the whole valid file → `created {0,0,0,0}`, all 5 rows
  skipped (case 11);
- psql side-effects: the paid household's membership is `ACTIVE` with
  `amount_due_cents=6500`, its payment is `6500 / OTHER /
  recorded_by=testadmin` linked by a `MEMBERSHIP` allocation (case 12);
  the unpaid household's membership is `PENDING_PAYMENT` with no payment
  (case 13); a blank `membershipType` creates the person + household and
  **no** membership (case 14); the `DEPENDANT` row is
  `is_statutory_member=false` while the `MEMBER` row is `true` (case 15);
- a UTF-8 BOM prefix plus a quoted field containing a comma parse
  correctly, with an accented given name (`Zoé`) intact on the round-trip
  (case 16); a body over 1 MB → **413** (case 17).

**Surprises recorded:**
1. CSV record numbering: with `setHeader().setSkipHeaderRecord(true)`,
   commons-csv's `getRecordNumber()` counts from the first *data* row, so
   the reported `line` is `getRecordNumber() + 1` to make the header
   line 1 and the first data row line 2 (what a treasurer sees in Excel).
2. Nested transactions: `PersonStore.create`/`HouseholdStore.create` open
   their own `jdbi.inTransaction` (a fresh connection), so reusing them
   inside the import's transaction would break atomicity. The apply
   writes people/households/memberships/payments with direct SQL on the
   one shared `Handle` — the CR anticipated "direct SQL where the shapes
   don't fit", and atomicity is why they don't.
3. Verify-script only (not the implementation): the CR-002 psql
   assertions first wrote `SELECT status ...` joining `membership` to
   `household` — both have a `status` column, so Postgres rejected it as
   ambiguous; qualified to `m.status`. And because people are never
   deleted, an unqualified `q=Aaron` search picked up Aarons from earlier
   runs; the assertions search the unique per-run family name instead.

**Browser walkthrough / real-data import: DONE** (Jason, 2026-07-17).
Beyond the scripted matrix, the society's actual member list was imported
through the admin UI and worked well — preview, apply, and the resulting
register lists. One UI finding fixed in passing: the household "Members"
button (and the person edit / new-household forms) un-hid their panel
below the fold, so it looked inert; opening a panel now scrolls it into
view (`admin.js` `reveal()`). Real member data lives outside the repo
(`ydhs/`, gitignored).

No compose/auth/Caddy changes, so no deploy Local smoke was required (per
the plan).

## Follow-ups / amendments

- Real import: confirm with the society which membership period the
  first live import targets, and current prices (V2 seed still carries
  the flagged 2025/26 placeholders).
- CR-003 may refactor the import's direct membership/payment SQL into
  its stores.
- Post-review notes (accepted as-is for v1): a `paid` row whose
  `membership_type_price` is 0 cents would fail the `payment.amount_cents
  <> 0` CHECK and roll back the whole apply as a 500 — left unguarded
  because no 0-priced type exists (seeded 4500/6500) and the failure is
  safe (nothing written); revisit if a free type is ever added. And
  `resolvePeriod`/`priceCents` run once per household group rather than
  once for the import — negligible at a hundred rows, cache if a much
  larger register ever imports.
- Partial-household dedup is now called out: when some people in an
  imported household already exist, they are skipped and a warning names
  how many will NOT be added to the newly-created household (v1 creates
  only; link them via the admin UI).
