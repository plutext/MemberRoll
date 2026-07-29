# CR 025: Person picker usability — slow-click race, gated buttons, full-name search

Status: IMPLEMENTED + VERIFIED (2026-07-30)

## Problem

Three defects conspired to make "re-add a person who left a household" look
impossible from `households.html`, even though the server has supported
leave-and-rejoin since V1 (two `household_person` rows; the partial unique
index only forbids two *current* rows):

1. **The picker drops slow clicks.** `wirePersonPicker` selects on the
   result row's `click` event, and closes the list from the input's `blur`
   after a 150 ms grace (`setTimeout(clear, 150)`). Pressing the mouse
   button blurs the input immediately; if press→release takes longer than
   150 ms — routine on a trackpad — the list is emptied *before* the
   `click` event fires, the handler is gone, and nothing is selected.
   Reproduced with Playwright: a 50 ms click selects, a 400 ms click loses
   the pick silently.
2. **Nothing tells the admin the pick failed.** The dependent button
   ("Add member", the household form's Save, the committee appointment
   Save) stays enabled with no captured person; clicking it yields only
   the generic "Choose a person" toast. A silent failed pick therefore
   reads as "this person cannot be added".
3. **Full-name queries match nothing.** `PersonStore.search` compares the
   query against `given_name`, `family_name`, `preferred_name` and email
   each as a whole pattern, so "Howard Ainsworth" (the natural thing to
   type) returns zero rows while "Howard" or "Ainsworth" match. The empty
   result renders as a silently hidden list — indistinguishable from the
   race in 1. The same limitation affects the People page search box
   (same endpoint).

## Approach

All UI/query usability; no schema, no new endpoints, no auth change.

1. **Select on `mousedown`, not `click`** (the classic autocomplete
   pattern): the handler calls `preventDefault()` — the input never
   blurs, the pick lands before any timer can run, and the timing window
   ceases to exist. The `blur` timeout remains only to close the list
   when clicking elsewhere. (On touch, the synthesized `mousedown` after
   a tap takes the same path.)
2. **Gate dependent buttons on a captured pick** (the reported wish):
   `wirePersonPicker` gains an optional gated-button id, recorded on the
   input (`data-gates`) so `resetPicker` and the input handler re-sync it
   from one place (`syncPickerGate`). The button is disabled whenever
   `data-person-id` is absent — on wire, on dialog open (every opener
   already calls `resetPicker`), and again the moment the text is edited
   (an edit already invalidates the pick). Gated: household add-member
   (`hdAdd`), household form Save (`householdSave`), committee
   appointment Save (`apptSave`). Deliberately NOT gated: the AGM slate's
   pickers (`agmSave`) — its offices are optional by design (CR-013
   warnings, not blocks), so an empty picker there is a valid state.
3. **Full-name matching** in `PersonStore.search`: add
   `given_name || ' ' || family_name ILIKE :pat` and the
   `preferred_name || ' ' || family_name` variant to the WHERE
   (`preferred_name` is nullable — a NULL concat is NULL, never a match;
   `given_name`/`family_name` are NOT NULL). "family, given" order is
   deliberately not matched — no observed need, and every clause costs a
   seq-scan comparison on a table sized in the hundreds.
4. **"No matches" feedback**: a ≥2-character query that returns nobody
   now shows one non-clickable muted row ("No matches — try part of one
   name, or an email") instead of a silently hidden list.

Re-add itself needs no server change: `HouseholdStore.addPerson` already
inserts a fresh row with a new `joined_household_date` and refuses only a
*current* member, which is the corrected behaviour the report asked for —
the household table shows an additional row for the person with the new
joined date, above the historical left-dated row(s). A rejoin does not
touch any existing membership's `membership_person` snapshot; segment
email (CR-005) and the card gate (CR-017) read live composition, so a
rejoined person is immediately covered there, and the next
rollover/new-membership picks them up.

## Verification plan

- Matrix (`CR25-*`): full-name search returns the fixture person
  (single-field queries keep working; a wrong full name returns zero;
  preferred+family matches; the LIKE-escape behaviour survives the new
  clauses).
- Playwright walkthrough (`tmp/cr025-fixtures/`): the previously-failing
  slow click (mousedown, hold 400 ms, mouseup) now selects; Add member /
  Save buttons disabled until a pick and re-disabled on edit;
  no-matches hint renders; full name typed into the picker finds the
  person; end-to-end remove → re-add on `households.html` shows the new
  current row plus the left-dated history row.

## Results

Implemented + verified 2026-07-30 (Fable 5).

### What changed

- `PersonStore.search`: the two full-name concat clauses.
- `admin.js`: `wirePersonPicker(inputId, gateButtonId)` — `mousedown` +
  `preventDefault()` selection, `data-gates`/`syncPickerGate` button
  gating (synced at wire, on edit, and in `resetPicker`), and the
  `picker-empty` no-matches row. Gates wired: `hfContact`→`householdSave`,
  `hdPersonId`→`hdAdd`, `apptPerson`→`apptSave`.
- `admin.css`: `.picker-empty` styling; hover highlight scoped to
  clickable rows.

### curl matrix (`server/verify-matrix.sh`, +7 CR25-* rows)

| Run | Result | Notes |
|---|---|---|
| 1 | PASS=944 FAIL=3 | the 3 = pre-existing flakes only |
| 2 (immediate re-run) | PASS=944 FAIL=3 | same 3; all CR25 rows green both runs — re-runnable |

The 3 failures both runs: row 27b (the Keycloak user-listing flake,
recorded since CR-013) and CR10-04g2/CR10-12c (the two documented
UTC-midnight "today" flakes — both runs fell inside the AEST-vs-UTC
date-disagreement window). All pre-existing, none CR-025-related.

CR25 rows: full "given family" name matches (total=1), substring across
the space matches, preferred+family matches, family alone still matches,
a wrong full name is 0, a literal `%` in the query stays escaped (0),
manager gets 200 on the search (CR-024 surface unchanged).

### Browser walkthrough (`tmp/cr025-fixtures/cr025-walkthrough.js`, Playwright)

**PASS=17 FAIL=0**, zero JS errors. Includes the previously-failing
baseline: a mousedown–400 ms hold–mouseup click on a picker result now
selects (before this CR it silently lost the pick — reproduced 2026-07-30
against the pre-CR build: 50 ms hold selected, 400 ms hold lost it).
Also proven: Add member disabled on dialog open / after a dead-end query
/ re-disabled the moment the text is edited, enabled only while a person
is captured; the no-matches hint row; full-name query through the picker;
end-to-end remove → re-add on `households.html` producing a NEW current
row with today's joined date above the kept left-dated history row;
`householdSave` and `apptSave` gated identically.

Walkthrough gotcha (the standing UTC bite, browser flavour): assert
"today" with `new Date().toLocaleDateString("sv")`, not
`toISOString().slice(0,10)` — the app stamps `current_date` in the JVM's
AEST zone and the ISO string is UTC, which disagrees for hours every day.
