# CR 022: Admin page reorganisation — People / Households / Renewals / System

Status: PROPOSED (2026-07-24)

## Problem

`admin/index.html` has grown into the panel's junk drawer. One page —
the landing page — carries six jobs: the People register, the
Households register, period administration (new period, journal price),
the rollover, the period's members list, and the CR-015 reconciliation
export. They arrived one CR at a time and each is fine alone, but
together the page is a long scroll where the daily task (find a
member, record a payment) sits below once-a-year administration
(create a period, roll over) and treasurer-only tooling
(reconciliation). The committee demo made the cost concrete: the page
is hard to hand to a volunteer.

The rest of the panel already follows a better pattern — one page per
job (`new-member`, `applications`, `email`, `committee`, `reports`,
`import`, `users`, `mail-settings`), all sharing `admin.js`, which
wires whatever sections the current page carries. This CR finishes the
job on the last and oldest page.

## Approach

A pure static-page reshuffle. **No API change, no schema change, no
new endpoints** — markup moves between pages, the shared `admin.js`
wiring is split along the same lines, and the menu/deep links are
updated. The multi-page mechanism is the one the panel has used since
CR-005 ("each page carries only its own sections, and the boot wires
whatever is present").

Where everything on today's `index.html` lands:

| Today on index.html | Destination | Menu label |
|---|---|---|
| People table + person form dialog | **`people.html`** (new) | People |
| Households table + household form + household detail dialogs | **`households.html`** (new) | Households |
| Members table + membership detail / payment / receipt / card dialogs, period context selector, AGM / labels / financial exports, Lapse all | **`index.html`** (slimmed) | Renewals |
| New period button + dialog, journal add-on price, rollover preview/apply | **`system.html`** (new) | System |
| Reconciliation export card + Xero mapping dialog | **`reports.html`** (existing) | — (Reports) |

### Why index.html stays the Renewals page (rather than a new renewals.html)

`/admin/` serves `index.html` — the landing page must be *something*,
and the members list is the daily driver (renewal season is when the
panel gets used). Keeping it on `index.html` also keeps the CR-010
success-screen deep link `index.html?membership=<id>&period=<id>`
working unchanged, and `renderMenu()`'s `"" → index.html` active-state
mapping needs no change. The alternative — a new `renewals.html` with
`index.html` as a redirect stub — buys a nicer filename at the cost of
a redirect hop and two more places to update; rejected.

### Menu

```
Renewals · People · Households · New member · Applications · Email ·
Committee · Reports · Import members · Users · Mail settings · System
```

The "Register & renewals" label dies. Renewals leads (it is the
landing page); System sits last, in the settings corner next to Mail
settings — it is the once-a-year page (open a period, price it, roll
over), deliberately out of the daily path so a volunteer never
scrolls past Apply rollover to reach a member.

## Design

### Section and wiring split

Today's two section ids become five, each gated by presence exactly
like every other page:

- `registerSection` splits into `peopleSection` (people.html) and
  `householdsSection` (households.html); `wireRegister()` splits into
  `wirePeople()` and `wireHouseholds()`.
- `renewalsSection` keeps its id and the members-list content on
  index.html; the period-admin markup moves out to `systemSection`
  (system.html) with a new `wireSystem()` (periodNew / periodForm /
  journalPriceSave / rollover buttons).
- The reconciliation card gets its own `reconciliationSection` id on
  reports.html and a `wireReconciliation()` gated on **that** id, not
  on `reportsSection` — the reports page already boots `wireReports()`
  and the two concerns should not be welded together a second time.

Known cross-wirings the split must fix (found by reading, worth
recording so review checks them):

- **`hmCreate` is wired in the wrong function today**: the "Create
  membership" button lives in the household detail dialog
  (registerSection markup) but is wired at the bottom of
  `wireRenewals()` — harmless while both sections share a page, a
  dead reference after the split. It moves to `wireHouseholds()`.
- **`savePerson` refreshes both tables** (`renderPeople();
  renderHouseholds()` — a person edit can change a household's
  displayed contact). Cross-section render calls like this must
  become presence-gated no-ops (`if (!document.getElementById(...))
  return;` at the top of each render function, the cheapest uniform
  fix) rather than being dropped — on a page that *does* carry both
  tables nothing changes, and a future page regrouping can't silently
  break refresh.

### Periods: two pages carry a `periodSelect`, differently

The members list is period-scoped, so the **Renewals page keeps the
period selector and summary line** — as read-only context ("which
year am I looking at"). The **System page carries its own
`periodSelect`** as the working selector for journal price and as
rollover context. Pages are separate documents sharing one script, so
the duplicate id is fine; what must change is `loadPeriods()`'s tail,
which today unconditionally runs `renderPeriodSummary()` (which
writes the `journalPrice` input — System-only), `fillTypeFilter()`
(Renewals-only) and `renderMemberships()` (Renewals-only). Each step
becomes presence-gated, extending the `if (!select) return` pattern
`loadPeriods` already uses for the import page.

The households page boots `loadPeriods()` too (no visible selector):
the household detail dialog's "New membership" period/type selects
(`hmPeriod`/`hmType`) fill from `periodsCache`, same as the email and
applications pages already do.

### Deep links

- `index.html?membership=<id>&period=<id>` — unchanged (index is
  still the page with the membership dialog).
- `index.html?household=<id>` becomes `households.html?household=<id>`.
  Three places: the producer in `nmShowSuccess` (new-member success
  screen), the producer in the applications detail dialog ("Open the
  created household"), and the boot-time consumer, which moves under
  the `householdsSection` gate.

### Reports page

The reconciliation card (preview / CSV / Xero journal / mark
reconciled) and the `xeroForm` dialog move under a `reconciliationSection`
on reports.html — it is an export for the treasurer, which is what
that page is for. The "Other exports" list at the bottom of
reports.html is rewritten: the reconciliation line goes (it is now on
this page), the AGM / labels / financial line now points at the
slimmed Renewals page.

### Headers, banner, titles

Each new page copies the standard header block (`h1` / `#status` /
`nav#menu` / `#message`) — the CR-021 sandbox banner and the menu come
for free, since both hang off the shared header and `admin.js` boot.
Page titles follow the existing convention: `memberroll admin —
people`, `— households`, `— renewals` (index, retitled from
"register & renewals"), `— system`.

### Docs

`docs/user-manual.md` references the old layout throughout ("Renewals
→ New period", "the Renewals page" for reconciliation, the
People/Households/Renewals ordering narrative) — it is updated in the
same commit, as are the reports.html prose above and the CLAUDE.md
architecture notes. No README change expected (it describes features,
not page layout).

## Out of scope / deliberately unchanged

- **No API change of any kind.** Every endpoint keeps its resource and
  path; the reconciliation endpoints stay on `AdminPaymentsResource`
  (CR-015's "the payments surface" decision is about the API, not the
  page that calls it).
- **No JS architecture change**: one shared `admin.js`, sections gated
  by presence. Splitting the script per page is tempting but is a
  different CR with different risks; this one only moves markup and
  splits wire functions.
- **No behaviour change** inside any moved feature — same dialogs,
  same buttons, same requests.
- `verify-matrix.sh` is untouched: it exercises the API, which does
  not change. It must simply stay green.

## Verification plan

1. **Full matrix** against the dev stack — expected identical to the
   pre-change baseline (no API surface touched); any diff is a
   regression.
2. **Grep audit**: after the split, every `getElementById` id
   referenced by a wire/render function either exists on the page(s)
   that call it or sits behind a presence gate; no references to
   removed markup remain.
3. **Playwright walkthrough** (the per-CR browser pass):
   - every page loads logged-in; the menu shows the new entries with
     the correct active item; the CR-021 sandbox banner appears on
     people/households/system when the redirect is set;
   - **People**: search, open, edit (member no., preferences), save;
   - **Households**: search, create, open detail, add person, set a
     preference, **create a membership from the dialog** (the
     `hmCreate` re-wiring and the periodsCache boot);
   - **Renewals** (index): period selector filters the table; status +
     type filters; open a membership, record a payment, Receipt…
     dialog, Card… dialog; AGM/labels/financial exports download;
   - **System**: create a throwaway period, save a journal price
     (summary line updates), rollover preview (writes nothing);
   - **Reports**: reconciliation preview, CSV download, Accounts…
     dialog opens, journal button visibility follows the saved
     mapping;
   - **Deep links**: new-member success → "Open household" lands on
     households.html with the dialog open, "Open membership" lands on
     index.html with the dialog open; applications detail → created
     household link.
4. Record results in this doc, per the workflow convention.
