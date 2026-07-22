# CR 019: Reports & exports — a reports surface and four new reports

Status: IMPLEMENTED + VERIFIED (2026-07-23; CR-018 landed first the same
day, as sequenced)

## Problem

The app answers "who is financial for period X" well and almost nothing
else. Existing read surfaces: the Renewals status view (period +
status/type/name filters) and five CSVs — AGM register, mailing labels,
financial (all period-scoped on `AdminPeriodsResource`), reconciliation
and the Xero journal (payment-scoped on `AdminPaymentsResource`,
CR-015). Gaps that have already cost us or are asked for:

- **Nothing person-level falls out.** The life-member import gap
  (CR-018) sat unnoticed for a month because no report shows people
  whose register presence and membership state disagree.
- **No cross-period view.** "Who was with us last year and hasn't
  renewed" — the actual chase list — requires diffing two CSVs by hand.
- **The statutory register of members** (clause 4) is specified as
  CR-011 Stage 1 and the committee confirmed it wanted, lower priority
  (2026-07-23).
- **Donations are invisible** outside the reconciliation export's
  DONATION column — the treasurer has no date-ranged donations view.
  (The committee's answer on the pay page's donation feature itself is
  still "to be advised" — that does not block reporting on donations
  already recorded.)

Discoverability is also poor: the exports live as buttons scattered in
the Renewals section; an admin looking for "reports" has no place to
look.

## Approach

One new admin page, four new admin-only CSV endpoints in the existing
export idiom (Commons CSV, exact-status matrix rows, no new
machinery). Deliberately NO report builder, NO stored reports, NO PDF —
CSV into a spreadsheet is the product, same as every existing export.

### Report A — Register of members (clause 4; delivers CR-011 Stage 1)

`GET /api/admin/export/register-of-members.csv` — not period-scoped:
one row per person who holds or has ever held a formal
(relationship_type MEMBER) membership place. Columns and derivations
exactly as specified in CR-011 Stage 1 (full name; best address =
preferred postal else primary email; date became a member = earliest
membership_person start with the stated imported-data limitation; date
ceased derived per clause 12; honours the CR-011 Stage 2 suppression
flag if/when that lands). This CR is the delivery vehicle; the
specification stays in CR-011 — implement to it, cross-reference
results in both docs.

### Report B — People without a current membership

`GET /api/admin/export/no-current-membership.csv?periodId=` — current
household people (not left, not deceased) with no `membership_person`
row in the given period. Columns: person, household, relationship,
email, phone, last period in which they held a place (blank = never).
This is the report that would have caught the CR-018 import gap, and
the standing answer to "who fell through the cracks at rollover"
(rollover skips households with no current members or an existing
membership — this shows the residue person-by-person).

### Report C — Unrenewed households

`GET /api/admin/export/unrenewed.csv?fromPeriodId=&toPeriodId=` —
households ACTIVE in the from-period whose to-period state is anything
else (no membership at all / PENDING_PAYMENT / LAPSED / CEASED), with
primary contact, email, phone, from-period type, and the to-period
status ("—" for none). The chase list for a ring-around; complements
(not replaces) the CR-005 segment email, which targets a single
period's statuses.

### Report D — Donations

`GET /api/admin/export/donations.csv?from=&to=` — payments (by
`received_date` range) carrying a DONATION allocation: date, payer,
method, donation amount, payment total, external transaction id, plus
a trailing total row (the CR-015 labelled-summary pattern). Negative
rows (reversals) appear and subtract — the export must sum to the
ledger, not flatter it.

### UI — `admin/reports.html`

A standalone admin page in the established pattern (`committee.html`,
`email.html`): one card per report with its parameters (period selects
populated from the periods cache; date pickers for donations) and a
download button, plus a links section pointing at the existing
period-scoped exports where they already live (links only — the AGM /
mailing-labels / financial / reconciliation surfaces do not move).
Admin nav gains a Reports link.

### Sequencing

Reports B and C first (operational value now, and B doubles as the
CR-018 remediation check), then A and D (committee said A is lower
priority; D's numbers only get interesting once online donations
accumulate).

### Rejected alternatives

- **A generic report builder / query params over arbitrary columns** —
  four known questions do not justify a DSL; the next question becomes
  Report E.
- **Folding CR-011 Stages 2–4 in** — they are blocked on committee
  answers (suppression requests, under-18 members) or are
  documentation-only; this CR takes only the export Stage 1, which is
  answer-independent.
- **Moving the existing exports onto the reports page** — churn with no
  gain; the Renewals-page buttons are where period work happens. Links
  suffice for discoverability.
- **Excel/PDF output** — every consumer so far opens CSV in a
  spreadsheet; formatting is their job.
- **A "last login" / self-serve-usage report** — tempting, but Keycloak
  owns login events and the app deliberately doesn't mirror identity
  state (CR-006); out of scope.

## Verification plan

Matrix rows (extend `server/verify-matrix.sh`, fixtures under
`tmp/cr19-fixtures/`, self-cleaning):

| # | report | case | expect |
|---|---|---|---|
| 1 | all | guest / user / noaud GET each endpoint | 401 / 403 / 401 per endpoint |
| 2 | A | admin GET | 200 text/csv; a current-member fixture with joined date; a ceased fixture with ceased date; email-only person shows email as address |
| 3 | A | imported member | joined date = earliest imported period start |
| 4 | B | person in a household with no membership this period | present, with last-held period |
| 5 | B | person on an ACTIVE membership | absent |
| 6 | B | left-household / deceased person | absent |
| 7 | C | household ACTIVE in from, nothing in to | present, to-status "—" |
| 8 | C | household ACTIVE in from, LAPSED in to | present, to-status LAPSED |
| 9 | C | household ACTIVE in both | absent |
| 10 | D | payment with DONATION allocation in range | present; trailing total equals sum |
| 11 | D | reversed donation | negative row present; total reflects it |
| 12 | D | donation outside date range / MEMBERSHIP-only payment | absent |
| 13 | all | unknown periodId / to before from / from after to | 400, not empty CSV |

Browser walkthrough: Reports page loads with periods populated; each
report downloads with parameters applied; links to existing exports
resolve; admin nav shows Reports.

## Results

Implemented 2026-07-23, all four reports in one pass (the B/C-first
sequencing was about priority, not staging — nothing forced a split).
Built as proposed: `AdminReportsResource` (`/api/admin/export/...`,
admin-only, Commons CSV, a bad parameter is a 400 JSON error never an
empty CSV) over a new read-only `ReportStore` (the ReconciliationStore
pattern), registered in `ApiApplication`; `admin/reports.html` + a
Reports nav entry, downloads via authenticated fetch → blob (the CR-017
bearer-auth bite — a plain `<a href>` would 401).

**Report A derivations pinned** (per CR-011 Stage 1's "to be pinned in
the implementation"; the spec doc cross-references back here):

- *Date became a member* = earliest `membership.start_date` among the
  person's formal (`is_statutory_member`) places — deliberately NOT
  `membership_person.start_date`, which is the row-creation date (a
  rollover run's date for carried members). For imported members this is
  the earliest imported period's start — the documented limitation,
  stated on the reports page and in the user manual.
- *Currency*: a person is current while they hold a formal place on an
  ACTIVE or PENDING_PAYMENT membership whose `end_date` has not passed
  (an unpaid member inside the year is still a member until lapsed or
  ceased — clause 12).
- *Date ceased* (non-current people): a CEASED last membership
  contributes its `ceased_date`; anything else (LAPSED, or simply never
  renewed) contributes the end of that last membership year, capped at
  today — the clause 12 non-payment mapping.
- Best address = preferred POSTAL of the person's current (else latest)
  household — the mailing-labels choice — else primary email; the Stage 2
  suppression flag does not exist yet, and `ReportStore.registerOfMembers`
  is the marked spot to honour it when it lands.

### Verification

Matrix: 45 CR19-* rows in `server/verify-matrix.sh` covering the whole
plan table — 12 auth-gate rows (guest 403 / user 403 / noaud 401 × four
endpoints — the codebase's actual guest behaviour, as with CR-018), 7
parameter-validation rows (unknown/missing period, to==from, to<from,
from>to, unparseable date), and the data rows on two far-future fixture
periods (2094/2095, unique `Rep$$` names): register current/ceased/
lapsed-capped-at-today/email-fallback/postal-preference/became-date rows,
report B present/absent/left/deceased rows, report C "—"/LAPSED/renewed/
ceased-in-from rows, donations payer/amount/reversal/out-of-range/
membership-only rows.

One assertion was corrected for re-runnability: payments are insert-only,
so the donations fixtures accumulate in the shared window across runs —
the trailing-total row is asserted to EQUAL THE SUM OF THE ROWS (which is
what the plan's row 10 means) rather than a fixed number.

Whole matrix: **PASS=719 FAIL=3** (twice), the 3 being the documented
pre-existing environmental flakes (Keycloak listing flake `27b`, UTC-date
rows `CR10-04g2`/`CR10-12c` on before-10:00-AEST runs). Row count
reconciles with the CR-018 baseline: 677 + 45 new = **722 = 719 + 3**.

Browser walkthrough (`tmp/cr019-fixtures/cr019-walkthrough.js`,
Playwright): **10/10** — nav shows Reports (active), period selects
populated with defaults, all four downloads carry the right CSV headers
and filenames through the blob path, donations shows the trailing total,
a to==from selection surfaces the server's 400 verbatim instead of saving
a file, and the Renewals link resolves. (One walkthrough lesson: the dev
DB's fixture-litter periods make the *default* from/to unpredictable, so
the download steps select explicit periods.)

Docs: user manual gained a "Reports" section; README a Reports bullet;
CR-011 records Stage 1 as delivered.

## Follow-ups / amendments

(dated additions)
