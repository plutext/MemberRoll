# CR 023: Persistent working period

Status: IMPLEMENTED + VERIFIED (2026-07-24)

## Problem

CR-022 put a period selector on two pages: System (the working
selector for journal price and rollover) and Renewals (labelled
"read-only context" in a comment, but in fact a second fully working
selector). Each page's selection lives only in that page's DOM: every
page load re-defaults to the first period in the list — which, since
`PeriodStore.list()` orders newest-first and the verify matrix seeds
far-future fixture periods, is not even reliably the period being
renewed. Nothing is remembered across a reload, let alone across
admins.

The actual shape of the work is: all renewals during a renewal season
pertain to ONE period. Which period that is changes once a year — a
System act, like the rollover — and every admin should see the same
one, on every device, without re-picking it.

## Approach

Make the working period a piece of server state, chosen on the System
page and stored in `app_setting` (the CR-014/CR-015/CR-007 blob
pattern — one atomic value, no migration earned):

- **`selected_period`** `app_setting` row, value `{"periodId": N}`.
- `GET /api/admin/periods` (existing) gains a top-level
  `selectedPeriodId` (null until first saved, or if the stored id no
  longer matches a period). One round trip — every page that loads
  periods learns the working period for free.
- `PUT /api/admin/periods/selected` `{periodId}` (admin-only, like the
  whole resource) validates the period exists (unknown → 400 naming
  the rule; it is a body reference, not a URI, so not a 404) and
  upserts the row. Returns `{selectedPeriodId}`.

Client model (shared `admin.js`):

- A module variable `currentPeriodId` replaces reading
  `#periodSelect`'s value; `selectedPeriodId()` returns it, so every
  existing consumer (members list, exports, lapse-all, rollover,
  journal price) is unchanged.
- `loadPeriods(selectId)` resolves it as: explicit `selectId` (deep
  link / post-save reselect) → the stored `selectedPeriodId` → the
  period covering today → newest. It then fills `#periodSelect` only
  where one exists.
- **Renewals (`index.html`) loses its selector.** The page shows the
  working period as context text (`periodSummary`, now prefixed with
  the period's role) plus a hint that it is set on the System page.
  The `index.html?membership=<id>&period=<id>` deep link still works:
  the `period` param sets `currentPeriodId` locally for that page view
  only — looking at last year's membership must never hijack the
  shared working period.
- **System's selector persists on change**: the `change` handler PUTs
  before re-rendering. Only the explicit act persists — creating a new
  period still reselects it locally (rollover targets it) but does NOT
  make it everyone's working period; that switch is deliberate, when
  renewals open.
- The other period selects with a "covering today, else newest"
  default (`nmPeriod`, `emPeriod`) and `hmPeriod` (which had no
  default logic) now prefer the stored working period first — the same
  resolution order as Renewals.

Deliberately NOT per-user: "across sessions and users" is the point —
the society renews one period at a time, and two admins disagreeing
about the working period is exactly the state this removes.

## Verification plan

Matrix rows (CR23-*, re-runnable; they end by pointing the selection
at the 2025-2026 fixture period, a sensible resting state for the dev
stack):

- `GET /admin/periods` response carries a `selectedPeriodId` key.
- `PUT /admin/periods/selected` with a known period → 200 echoing it;
  subsequent GET reflects it (the persistence claim).
- Unknown period id → 400; non-object body → 400; missing periodId →
  400.
- Role checks: guest 403, member 403, noaud 401 on the PUT.

Browser walkthrough: System page — change the period, reload, land on
the same period; open Renewals in a second session — same period, no
selector, context line names it; deep-link `?period=` to another
period does not change what System (or a fresh Renewals load) shows;
new-member and household "new membership" period selects default to
the working period.

## Results

2026-07-24, dev stack (Keycloak 18081 / Postgres 5433 / Mailpit 18025,
cargo on 18080):

- **Matrix**: full `server/verify-matrix.sh` run **PASS=858 FAIL=1**;
  the 1 is `27b list has testuser`, the documented pre-existing
  Keycloak-listing flake (unrelated — an additive-only CR). All 12 new
  CR23-* rows green, plus 3 new static-page rows (33k/l/m) for the
  CR-022 pages that had none.
- **Walkthrough** (`tmp/cr023-fixtures/cr023-walkthrough.js`,
  Playwright): **25/0, zero JS errors** — System boots on the stored
  period, an explicit change persists ("for all admins" message +
  server state) and survives reload; creating a period reselects
  locally WITHOUT persisting; Renewals has no selector and labels the
  stored period "Working period 2025-2026" (a `?period=` deep link
  shows "Viewing period" and leaves the store untouched; the
  membership deep link still opens its dialog); `nmPeriod`/`emPeriod`/
  `hmPeriod` all default to the working period.
- Implementation notes: `whom()`/upsert copied from CR-014's
  `AdminMailSettingsResource` (same `app_setting` discipline);
  `loadPeriods` no longer early-returns on pages without a
  `periodSelect` — its tail was already presence-gated by CR-022, which
  is what makes the selector-less Renewals page work. The stale
  "Renewals → New period" import hint was corrected to System in
  passing.
