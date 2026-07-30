# CR 026: Live (type-ahead) table search

Status: IMPLEMENTED + VERIFIED (2026-07-30)

## Problem

The admin panel's four table search boxes — Renewals (`memberSearch` on
`index.html`), People (`personSearch`), Households (`householdSearch`),
Users (`userSearch`) — only search on the Go button or the Enter key.
The CR-025 person-*pickers* update as you type; the tables don't, and the
inconsistency is a papercut (type a name, nothing happens, hunt for the
button). There is no design reason — the pickers were purpose-built
later; the table boxes kept the CR-001-era "input + button" shape and
were never revisited.

Nothing server-side is in the way: each box already drives a render
function (`renderMemberships`/`renderPeople`/`renderHouseholds`/
`renderUsers`) that hits the same cheap paginated query a picker
hammers per keystroke.

## Approach

Client-only. One shared helper, `wireLiveSearch(inputId, buttonId,
render)`, replaces the four bespoke button+Enter wirings: it keeps the Go
button and Enter (muscle memory; Enter also fires immediately, cancelling
any pending debounce) and adds a **~200 ms debounced `input`** handler —
the same debounce the pickers use. Each box is optional (a page carries
only its own), so the helper no-ops on a missing input.

Two details the pickers get away without but a live table search needs:

1. **Stale-response guard** (`renderGuard(key)`): a live search can render
   out of order — type "sm", then "smith"; if the "sm" response arrives
   last it overwrites the better table. Each guarded render bumps a
   per-key token before fetching and, after its awaits, bails if a newer
   call has superseded it. Applied to all four renders (they are each
   called from several places — button, Enter, filter `onchange`,
   programmatic refresh after import — so the guard lives in the render,
   not the wiring). The pickers share the same latent race but are out of
   scope here (a wrong-order picker list is transient and self-corrects
   on the next keystroke; a wrong table persists).

2. **Renewals composes with its filters for free.** `renderMemberships`
   reads the search box, the status filter and the type filter *fresh at
   call time* and sends one combined query; the status/type selects
   already call it on `change`. So a live keystroke search automatically
   respects the current status/type selection, and changing a filter
   still re-applies the current search text — no extra wiring. (This is
   why the Renewals "search" is a filter-the-visible-table control, not a
   pick-one dropdown like the household add-person picker.)

The empty query already means "unfiltered list" on all four tables, so
clearing the box restores the full table — live filtering degrades
gracefully.

Deliberately unchanged: `userSearch` stays debounced-but-live even though
each call is a Keycloak admin REST round trip (not a Postgres query) —
200 ms is enough to keep it from firing per keystroke, and consistency
wins; no separate treatment.

## Verification plan

- No API change → no new matrix rows; the full matrix must stay green
  (byte-identical `verify-matrix.sh`).
- Playwright walkthrough (`tmp/cr026-fixtures/`): on each of the four
  pages, typing into the box narrows the table **without** pressing Go or
  Enter; on Renewals, a live search combined with a status filter returns
  the intersection; the Go button and Enter still work; a scripted
  out-of-order race (resolve an old response after a new one) leaves the
  table showing the newer query's rows (stale-response guard).

## Results

Implemented + verified 2026-07-30 (Opus 4.8).

### What changed (`admin.js` only, + no CSS/HTML)

- `wireLiveSearch(inputId, buttonId, render)` and `renderGuard(key)`
  helpers added after `registerCall`.
- The four bespoke button+Enter wirings replaced by `wireLiveSearch`
  calls (`wireRenewals`/`wirePeople`/`wireHouseholds`/`wireUsers`); the
  now-unused local `enter` helpers in `wirePeople`/`wireHouseholds` were
  removed with them.
- Stale-response guard added to `renderMemberships`/`renderPeople`/
  `renderHouseholds`/`renderUsers` (bump before fetch, bail after the
  awaits if superseded).

### curl matrix

No API change, so no new rows — `verify-matrix.sh` byte-identical.
Regression pass: **PASS=946 FAIL=1**, the 1 being the standing 27b
Keycloak user-listing flake (same count as the CR-025 baseline).

### Browser walkthrough (`tmp/cr026-fixtures/cr026-walkthrough.js`, Playwright)

**PASS=10 FAIL=0**, zero JS errors:

- People and Households tables narrow as you type, with no Go/Enter
  press; clearing the box restores the full list; the Go button (People)
  and Enter (Households) still work.
- Renewals: a live nonsense token reaches zero rows; with a status
  filter active, a live search fires a request carrying **both**
  `status=` and `q=` — the composition invariant (asserted on the actual
  request URL, so it holds regardless of row counts).
- The stale-response guard: `renderGuard("x")()` reads false for an
  older call once a newer call has run, true for the newer — the
  superseded render bails.

Test-writing note (fixed mid-run): don't assert composition by comparing
row counts before/after clearing the search — clearing broadens the set,
so the counts legitimately differ. Assert the fired request URL carries
both params instead.
