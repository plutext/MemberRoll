# CR 024: Manager role access + Admin sub-menu

Status: IMPLEMENTED + VERIFIED (2026-07-25); auth-diff review passed same day

## Problem

The `manager` realm role has existed since the template — grant-only,
granted from the Users page (`PUT /api/admin/users/{id}/manager`) — but
it has zero API surface: every admin resource is class-level
`@RolesAllowed("admin")`, so granting `manager` today changes nothing.
The society wants day-to-day membership work delegable to committee
members (a membership secretary recording renewals, a secretary
processing applications) without handing them system configuration:
member import, Keycloak user administration, the SMTP relay, and period
admin (create/rollover/journal price/working period) must stay
admin-only.

The menu is the visible half but the small half. Static pages cannot be
role-gated server-side (bearer auth, no cookies — the standing bite), so
the real change is opening a defined subset of the admin API to
`{"admin", "manager"}`; the panel then filters its menu and page
elements to match, and the four admin-only pages move under an
**Admin ▾** sub-menu so the flat menu stops growing.

## Role model

- **manager** — day-to-day membership operations: the register (people,
  households), renewals (memberships, payments, receipts, cards, pay
  links), new members, applications, segment email, the committee
  register, reports.
- **admin** — everything manager can do, plus system configuration and
  identity administration: import, users/claims/manager grants,
  self-serve provisioning, mail settings, period admin, reconciliation.

Recorded decisions (from the 2026-07-25 discussion):

- **Managers record AND reverse payments.** That is the membership-
  secretary job; exposure is bounded because corrections are negative
  payments (never edits) and `recorded_by` names the actor on every row.
- **Committee page → manager.** Register maintenance like
  People/Households; the secretary who minutes the AGM is exactly a
  manager.
- **Reconciliation + Xero mapping stay admin-only** (treasurer/system
  territory), even though they render on the shared Reports page — the
  card hides for managers.
- **Application settings (`formEnabled`, alert mailbox) stay
  admin-only** — system config that happens to render on the
  Applications page; the card hides for managers.
- **Keycloak-link endpoints stay admin-only** (both GET and DELETE on
  `/admin/people/{id}/keycloak-link`) — identity plumbing, consistent
  with provisioning living on the admin-only Users page; the person
  dialog hides that block for managers.

## Approach

### Server: annotation split, fail-closed

Every straddling resource KEEPS its class-level `@RolesAllowed("admin")`
and adds method-level `@RolesAllowed({"admin", "manager"})` on the
opened methods only (method-level overrides class-level under
`RolesAllowedDynamicFeature`). This is deliberate: an unannotated new
method defaults to admin-only — fail closed — instead of inheriting a
widened class annotation.

Opened wholesale (class annotation becomes `{"admin", "manager"}` —
every method is manager territory):

| Resource | Pages served |
|---|---|
| `AdminMembershipsResource` (create, get, update, pay-link, card ×3) | Renewals, Households |
| `AdminHouseholdsResource` (incl. preferences) | Households, New member |
| `AdminNewMemberResource` | New member |
| `AdminEmailResource` (templates, footer, preview, sends, resume) | Email |
| `AdminCommitteeResource` (incl. `/contacts`) | Committee |
| `AdminReportsResource` (4 CSVs) | Reports |

Method-level splits:

| Resource | manager+admin | admin only |
|---|---|---|
| `AdminPeriodsResource` | `GET /periods` (carries `selectedPeriodId`), `GET {id}/memberships` (statusView), the three period exports (agm-register, mailing-labels, financial) | create, `PUT {id}` (prices/journal price), rollover preview/apply, lapse-unpaid, `PUT /selected` (CR-023 working period) |
| `AdminPaymentsResource` | record payment, list, `GET`/`POST {id}/receipt` | reconciliation exports (csv + JSON), xero-journal.csv, `GET`/`PUT xero-account-mapping`, `POST reconcile` |
| `AdminPeopleResource` | list, create, `GET`/`PUT {id}`, preferences | `GET`/`DELETE {id}/keycloak-link` |
| `AdminApplicationsResource` | list, `GET {id}`, approve, reject, delete | `GET`/`PUT settings` |
| `AdminMailSettingsResource` | new `GET sandbox` (below) | everything else |

Unchanged, admin-only throughout: `AdminUsersResource`,
`AdminImportResource`, `AdminSelfServeResource`, `AdminPingResource`.

The CR-023 shape survives intact: a manager's pages learn the working
period from `GET /periods` but only the System page (admin-only) can
change it — which CR-023 already decided, for everyone.

### New endpoint: sandbox visibility for managers

The CR-021 ambient banner reads `GET /admin/mail-settings` and is
silent on failure — a manager would get a silent 403 and send segment
email to real addresses with **no sandbox banner**, defeating exactly
what the banner exists for. Fix: `GET /api/admin/mail-settings/sandbox`
(`@RolesAllowed({"admin", "manager"})`) returning ONLY
`{"redirectTo": "..."|null}` — no relay host/username/passwordSet leak.
It reads the same `smtp_settings` blob (`redirectTo` is PAGE-only, so
an absent row is honestly `null`). `refreshSandboxBanner` switches to
it for every role — one code path, and the admin's banner behaviour is
unchanged.

### Client (`admin.js`)

- **Boot gate** (`showIdentity`): accept `admin` OR `manager`; anyone
  else still bounces to `../web/`. The caller's roles are kept in a
  module variable (`isAdmin`).
- **Per-page gate**: each `MENU` entry gains `adminOnly: true` where it
  applies; the boot looks up the current page in `MENU` and bounces a
  manager landing on an admin-only page (deep link, stale bookmark) to
  `index.html` — a panel user belongs in the panel, not `../web/`.
- **Menu**: `renderMenu` renders the non-`adminOnly` items flat —
  `Renewals · People · Households · New member · Applications · Email ·
  Committee · Reports` — and, for admins only, an **Admin ▾** item
  holding `Import members · Users · Mail settings · System` (System
  stays last, the CR-022 settings-corner ordering, now inside the
  sub-menu). Hand-rolled per house style: a `<details>`/`<summary>`
  dropdown styled in `admin.css`, absolutely positioned, no library —
  `<details>` because it works on touch without hover hacks. Managers
  never see the item at all (no link that 403s, ever).
- **Element hiding on shared pages**: the three manager-visible pages
  carrying admin-only cards mark them `data-admin-only` in markup
  (Applications settings card, Reports reconciliation card, the person
  dialog's Keycloak-link block); the boot sets `hidden` on all
  `[data-admin-only]` when the caller is not admin. The reconciliation
  card's wiring is already gated on its own section (CR-022), so hiding
  is enough.

### Keycloak / fixtures

- Dev realm JSON gains **`testmanager`** (password = username, the
  standing convention; `realmRoles: ["manager"]`). The `manager` role
  already exists in both dev and prod realms, and prod strips test
  users via the existing render mechanism — so there is **no prod realm
  change to mirror**.
- CLAUDE.md / GETTING-STARTED test-identities line gains `testmanager
  (manager)`.

## Verification plan

Matrix (CR24-*, re-runnable; `MANAGER=$(tok testmanager test-cli)`
minted beside `ADMIN`). The existing rows are untouched — guest /
member / viewer / noaud expectations don't change anywhere:

- **Opened endpoints**: for each resource in the tables above, a
  representative manager call answers 2xx (list people, create+manage a
  membership, record and reverse a payment, receipt GET, card info,
  new-member, application approve path, email template save + test
  send, committee list, one report CSV, periods GET carrying
  `selectedPeriodId`, statusView, one period export).
- **Still-closed endpoints**: manager → 403 on EVERY admin-only
  endpoint — users (incl. the manager grant itself: a manager cannot
  mint managers), import preview, self-serve preview, mail-settings
  GET/PUT/test, periods create / `PUT {id}` / rollover preview /
  lapse-unpaid / `PUT selected`, reconciliation csv + JSON + xero
  journal + mapping GET/PUT + reconcile, people keycloak-link
  GET/DELETE, applications settings GET/PUT.
- **Sandbox endpoint**: manager 200 with `redirectTo` reflecting the
  saved blob (set → value, cleared/absent row → null); response carries
  NO other keys (the no-leak claim); guest/member 403, noaud 401; admin
  200 (same body).
- Fail-closed spot check: the class-level annotations still hold —
  e.g. manager 403 on `/admin/ping`.

Browser walkthrough (Playwright, both identities):

- **testmanager**: menu shows the 8 flat items and NO Admin item;
  Renewals loads with the working-period context line; record + reverse
  a payment, open a receipt; Applications page shows the queue but no
  settings card; Reports page shows the four reports but no
  reconciliation card; person dialog shows no Keycloak-link block; deep
  link to `system.html` (and `users.html`) bounces to `index.html`;
  with a sandbox redirect saved (as admin, via API), the manager's
  pages show the SANDBOX banner.
- **testadmin**: Admin ▾ opens with the four items and navigates;
  everything previously verified renders as before (the sub-menu is the
  only visible change); sandbox banner still appears via the new
  endpoint.

## Results

Implemented + verified 2026-07-25 (Opus 4.8). The agreed pre-merge review
pass over the auth diff (Fable 5, same day) confirmed: class-level
`@RolesAllowed("admin")` survives on all five split resources, the diff's
only annotation removals are the six class-level widenings, the opened
method set matches this doc exactly, the sandbox endpoint's body carries
only `redirectTo`, and no other resource lost or gained an auth annotation.

### What was built

- **Server, wholesale-open** (class `@RolesAllowed("admin")` →
  `{"admin", "manager"}`): `AdminMembershipsResource`, `AdminHouseholdsResource`,
  `AdminNewMemberResource`, `AdminEmailResource`, `AdminCommitteeResource`,
  `AdminReportsResource`.
- **Server, method-level splits** (class stays `admin`, opened methods get
  `@RolesAllowed({"admin", "manager"})` — fail-closed for any unannotated new
  method): `AdminPeriodsResource` (list + statusView + the three period
  exports), `AdminPaymentsResource` (record, list, receipt GET/POST),
  `AdminPeopleResource` (list/create/get/put + preferences; keycloak-link
  stays admin), `AdminApplicationsResource` (list/get/approve/reject/delete;
  settings stay admin).
- **New endpoint** `GET /api/admin/mail-settings/sandbox`
  (`{"admin", "manager"}`) returning only `{redirectTo}` — no relay
  host/username/passwordSet leak. `refreshSandboxBanner` switched to it for
  every role.
- **Client** (`admin.js`): `isAdmin` module var; `showIdentity` accepts
  `manager`; `MENU` gained `adminOnly` flags; boot bounces a manager who
  deep-links an admin-only page to `index.html`; `renderMenu` renders the 8
  flat items and, for admins only, a hand-rolled **Admin ▾** `<details>`
  dropdown (`Import members · Users · Mail settings · System`); a new
  `applyAdminOnlyVisibility` sets `hidden` on `[data-admin-only]` for managers;
  the reconciliation wiring and the applications-settings load are gated on
  `isAdmin`; the person dialog's Keycloak-link toggle is gated on `isAdmin`.
- **CSS** (`admin.css`): the Admin ▾ dropdown (absolutely-positioned panel,
  touch-safe, no library).
- **Markup**: `data-admin-only` on the Applications form-settings card, the
  Reports reconciliation section, and the person dialog's `#pfLinkWrap`.
- **Fixtures/docs**: `testmanager` (realmRoles `["manager"]`, password =
  username) added to the dev realm JSON (no prod realm change — `manager`
  already exists, prod strips test users); CLAUDE.md / GETTING-STARTED /
  server README test-identity lines updated.

### curl matrix (`server/verify-matrix.sh`, +79 CR24-* rows + 2 hygiene rows 30e/30f)

Against the dev stack (fresh realm import so `testmanager` exists):

| Run | Result | Notes |
|---|---|---|
| 1 | **PASS=940 FAIL=0** | fully green |
| 2 (immediate re-run) | PASS=939 FAIL=1 | the 1 = the pre-existing **row 27b Keycloak user-listing flake** (recorded in CR-023's results), unrelated to CR-024 |

**79/79 CR24-* rows green on both runs.** They split three ways, all
first-class:

- **(a) Opened** — a manager gets the same 2xx an admin does across the
  register, new-member, record+reverse a payment, receipt GET, admin card
  info, periods GET (carrying `selectedPeriodId`), statusView, the three
  period exports, committee list/contacts, two report CSVs, email
  template save + preview + test-send-matches-admin; approve/reject proven
  reachable by `manager == admin` on a bogus id (mail-state-agnostic).
- **(b) Still-closed sweep** — the safety net: manager **403 on every
  admin-only endpoint** (CR24-40…68), including the manager grant itself
  (a manager cannot mint managers), import preview/apply, self-serve
  preview/provision, mail-settings GET/PUT/DELETE/test, period
  create/update/rollover×2/lapse-unpaid/`PUT selected`, reconciliation
  csv+JSON+xero-journal+mapping GET/PUT+reconcile, people keycloak-link
  GET/DELETE, application settings GET/PUT, and the class-level fail-closed
  spot check on `/admin/ping`.
- **(c) Sandbox endpoint** — manager 200 reflecting the saved blob
  (set → value, cleared/absent → null), body carries ONLY `redirectTo`
  (no-leak asserted twice), guest/member 403, noaud 401, admin 200 same body.

**Re-run hazard found + fixed (newly exposed by CR-024):** the matrix's
row 29 grants `testviewer` the `manager` role and never revoked it. Harmless
before this CR (manager had no API surface), but now `/admin/people` is
manager-accessible, so a re-run's role-less `$VIEWER` negative tests (rows
36/37) saw 200. Added rows **30e/30f** to revoke the grant after the
survival assertion, restoring re-run hygiene; both runs above are with the
fix in place.

### Browser walkthrough (`tmp/cr024-fixtures/cr024-walkthrough.js`, Playwright)

**PASS=26 FAIL=0**, zero JS errors on every page.

- **testmanager**: menu is exactly the 8 flat items with **no Admin ▾**;
  Renewals shows the working-period line and rows; the membership dialog +
  Receipt dialog open (receipt number shown); the Applications form-settings
  card, the Reports reconciliation card and the person dialog's Keycloak-link
  block are all hidden; `system.html` and `users.html` both bounce to
  `index.html`; with a sandbox redirect saved (admin, via API) the manager's
  page shows the SANDBOX banner via the new endpoint.
- **testadmin**: the Admin ▾ item appears, opens to the four items, and
  navigates to System; the sandbox banner still renders via the new endpoint.

Screenshots: `cr24-mgr-renewals.png`, `cr24-mgr-sandbox.png`,
`cr24-admin-menu.png` in the session scratchpad.
