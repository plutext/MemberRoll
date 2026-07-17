# CR 009: UI polish — Pico CSS, dialog forms, and the UX rough edges

Status: PROPOSED

Out-of-band CR: the roadmap reserves 004–008 for the feature sequence
(Stripe → email → self-serve → application form → hardening); this CR is
orthogonal to all of them and can land at any point. Landing it before
CR-006/007 is attractive because those CRs add the first pages the
public and ordinary members see.

## Problem

The forms are functional but ugly. The three admin pages
(`admin/index.html`, `admin/users.html`, `admin/import.html`) and the
member web page (`web/index.html`) share a deliberately minimal
hand-rolled look (~30 lines of CSS): unstyled-looking inputs, inline
fieldsets that push the page around when they open, raw person-id
number inputs, and status shown as bare text. Fine for verifying the
API; not something to put in front of the society's volunteers, and
CR-006/007 will add member-facing pages that inherit whatever baseline
exists.

Two distinct problems, and this CR addresses both:

1. **Visual**: typography, spacing, form controls, tables, buttons all
   look default-browser.
2. **UX structure**: some of the "ugly" is not CSS at all — inline
   forms instead of modals, typing integer person ids by hand, statuses
   without visual weight.

## Approach

### Options considered for the visual half

The pages are clean semantic HTML (`fieldset`/`legend`/`label`/
`table`, no div soup, framework-free classic JS, no build step,
everything vendored into the war). That constraint set filtered the
options as follows.

**Chosen — Pico CSS (classless build), vendored.**
[Pico](https://picocss.com) (MIT) styles semantic HTML directly:
`input`, `select`, `button`, `table`, `nav`, `dialog` all get a
polished modern look with near-zero markup changes. One ~80KB CSS file
vendored into `shared/`, linked before `admin.css`; `admin.css`
shrinks to genuine overrides. Built-in dark mode
(`prefers-color-scheme`), real focus states, consistent spacing, no
JS, no build step. Chosen precisely because the markup is already
semantic — the alternatives below would require rewriting every
element's class list, Pico does not. Classless build (not the default
class-based one) so bare elements are styled; the only structural
requirement is a `<main>` wrapper, which Pico uses as its container.

**Rejected — component frameworks (Bootstrap 5, Bulma).** Both MIT,
both work vendored without a build, and both give a more
"product-looking" vocabulary (cards, badges, button groups, modals).
But the cost is touching every element — `class="form-control"`,
`class="btn btn-primary"`, `class="table table-striped"`, grid
wrappers — a rewrite of all four pages for a marginal gain over Pico
at this app's scale. (Bootstrap 5's JS is vanilla, so it would not
violate the framework-free-JS convention; it is the markup churn that
kills it.)

**Rejected — Tailwind.** Wants a build step; the CDN "play" version is
explicitly not for production. Wrong shape for this project.

**Rejected — web components (Shoelace / Web Awesome).** Framework-free
custom elements (`<sl-input>`, `<sl-dialog>`) and genuinely slick, but
it rewrites the markup *and* how `admin.js` reads values, and is the
largest dependency of the lot. Not worth it unless we someday want its
fancier widgets (searchable selects, drawers).

**Rejected — hand-rolled modern CSS only.** ~150 lines of custom
properties, `accent-color`, focus rings, card fieldsets, sticky table
headers would get most of the way with zero dependencies — but it is
design work maintained forever that plateaus below what Pico gives for
free. Kept as the fallback if Pico surprises us.

### The other half: UX structure (independent of any framework)

1. **Edit forms become `<dialog>` elements** instead of inline
   fieldsets that push the page around. Native `<dialog>` needs no
   library, gives a proper modal with backdrop and Esc-to-close, and
   Pico styles it. Applies to: `personForm`, `householdForm`,
   `householdDetail`, `periodForm`, `membershipDetail`, and the nested
   `paymentForm` (dialogs stack natively).
2. **The raw person-id number inputs go** (`hfContact` primary contact,
   `hdPersonId` add-member): replaced by a type-ahead search that hits
   the existing `GET /api/admin/people?q=` endpoint and picks a person
   by name. Typing an integer id is the most admin-hostile thing on the
   page. No API change.
3. **Status becomes colored badges** in the memberships table (Unpaid
   amber, Paid green, Lapsed grey, Applied blue, Ceased grey) and for
   the Verified column on the users page. One CSS class plus a `<span>`
   in the row renderers.

### Design

New/changed files:

- `shared/pico.classless.min.css` — vendored latest Pico v2 classless
  build, MIT license header retained in the file.
- `admin/admin.css` — shrinks to overrides only: the load-bearing
  `[hidden] { display:none !important }` rule (still needed for
  non-dialog hidden sections; dialogs use their own open/close
  mechanism and sidestep it), menu active state, `#status`/`#message`
  colors expressed as Pico CSS variables so dark mode works, and the
  new `.badge-*` classes.
- `web/index.html` — the inline `<style>` block shrinks the same way
  and the page links the shared Pico file. The claim modal keeps its
  existing overlay mechanism (it has shipped-bug history around
  `[hidden]`; not worth re-plumbing) but its hardcoded `#fff`/`#a00`
  colors move to Pico variables.
- All four pages: content wrapped in `<main>` (Pico's container),
  `<h1>` block in `<header>`.
- `admin/index.html` + `admin.js` — fieldset→dialog conversion (the
  `hidden`-attribute toggles become `showModal()`/`close()`), the
  person picker, badge rendering. `users.html`/`import.html` have no
  inline forms, so they only get the Pico/`<main>`/badge treatment.

Explicitly unchanged: every API endpoint, `auth.js`, `sha256.js`, the
claim flow, the menu-driven page structure. No realm changes. Colors
that carry meaning (`#status.warn`, `#message.error`) keep their
red/green semantics, restated in Pico variables.

Risks / gotchas to respect during implementation:

- Pico dark mode will surface any remaining hardcoded light-theme
  colors (e.g. the claim card's `#fff`) — sweep for hex colors and
  replace with Pico variables or scope them out.
- Pico's classless build styles `fieldset` borderless; grouping that
  the old bordered fieldsets provided must come from the dialogs.
- The `[hidden]` !important rule stays — an author `display` rule on a
  non-dialog element would otherwise beat the attribute again (shipped
  bug, see CLAUDE.md).
- `verify-matrix.sh` touches no HTML/CSS and must stay green,
  unchanged, as the no-API-regression proof.

## Verification plan

No server code changes, so the scripted half is a regression gate and
the browser walkthrough is the substance.

Scripted: `server/verify-matrix.sh` against the running dev stack —
expect PASS=205 FAIL=0, identical to CR-003.

Browser walkthrough (desktop Chromium/Firefox + one phone via LAN IP,
each in both light and dark `prefers-color-scheme`):

| # | page | check |
|---|---|---|
| 1 | all four | Pico look applied: styled inputs/buttons/tables, readable focus ring when tabbing, no default-browser controls left |
| 2 | all four | dark mode: no illegible hardcoded-color patches; `#status`/`#message`/badges legible in both themes |
| 3 | all four | menu renders, active page highlighted, layout survives narrow (phone) viewport without horizontal scroll |
| 4 | admin index | New person opens as a modal dialog with backdrop; Esc and Cancel both close it; Save still creates the person and refreshes the table |
| 5 | admin index | New household: primary contact chosen via type-ahead person search (no raw id field); picking a result fills the form; save works |
| 6 | admin index | household detail dialog: add member via person picker; nested "sign up for period" controls still work |
| 7 | admin index | membership detail dialog opens; Record payment opens the nested payment dialog on top; saving a payment closes it, refreshes the payments table and status |
| 8 | admin index | New period dialog: prices grid renders, create works |
| 9 | admin index | memberships table shows status badges with the agreed colors; filter by status still works |
| 10 | admin users | table styled, Verified shown as badge, verify/manager toggles still work |
| 11 | admin import | period picker + file input styled; preview/apply flow unchanged, report readable |
| 12 | web | notes editor styled; claim modal still appears for a role-less user, is dismissible, and — regression for the shipped bug — when hidden it does NOT eat taps (click something under where it was) |
| 13 | web (phone, `http://<LAN-IP>`) | login works (sha256 fallback path unaffected), page usable at 390px width |
| 14 | admin index | every previously-`hidden` section still starts hidden and toggles correctly (the `[hidden]` rule survived the CSS shrink) |

## Results

(to be filled in after implementation)

## Follow-ups / amendments

- CR-006/007 pages should start from this baseline (link Pico + the
  shared conventions) rather than growing their own styles.
