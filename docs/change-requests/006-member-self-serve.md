# CR 006: Member self-serve — "my membership" page, pay from there

Status: IMPLEMENTED + VERIFIED (2026-07-18)

## Problem

After CR-005 a member's whole online experience is the magic pay link:
useful, but stateless and one-shot. The roadmap's item 7 is the opt-in
complement: a Keycloak-linked "my membership" page where a logged-in
member sees their household's membership status and can start a payment
without waiting for (or finding) a renewal email. Scope per the
roadmap: **view + pay only** — self-service editing of contact details
is explicitly out of scope for v1.

The blocker has been the open question recorded in ROADMAP.md: what
links a Keycloak account to a `person` row? The original working
assumption was "email match at login with admin confirmation". This CR
adopts a stronger model, agreed 2026-07-18 (Jason).

## Principles (agreed 2026-07-18)

The linking rule, stated as principles — the design below follows from
these:

1. **Self-registered users must verify their email.** The realm turns
   on Keycloak's Verify Email required action; an account whose address
   is unconfirmed never gets linked to a person. (Mailbox control is
   the same trust anchor CR-004's magic links already rest on.)
2. **Imported members' addresses are treated as verified.** The society
   has corresponded with these addresses for years — better evidence
   than a click-through. Accounts provisioned from the register are
   created `emailVerified: true`.
3. **The register provisions Keycloak accounts, not the other way
   round.** For each qualifying member email, a Keycloak user is
   created (or an existing verified one adopted) by an admin-triggered,
   idempotent provisioning step, and `person.keycloak_subject` is
   written **at that moment** — the link is established at creation,
   with the person known, so there is no match-at-login heuristic, no
   ambiguity to resolve, and no admin confirmation queue. First login
   is via Keycloak's password reset (see "First login" below), which
   satisfies the "set your own password" intent.
4. **One email address may be in use in at most one household.** Within
   a household an address may be shared (couples do); across households
   it is a conflict — provisioning skips and reports it, never guesses.
   In Keycloak the shared within-household address belongs to exactly
   one account, attributed by the CR-005 rule: the household's primary
   contact wins, else the lower person id.
5. **Self-serve is MEMBER-only.** Only people with a current
   `relationship_type` MEMBER row are provisioned/linked — the same
   principle as the 2026-07-18 voting-rights and segment-email
   decisions: the formal member is who the society corresponds with.
   PARTNER/DEPENDANT/OTHER are covered by the membership but do not get
   accounts.
6. **Silent provisioning + forgot-password first login** (decided over
   an invite-email blast): provisioning sends nothing. A member who
   wants self-serve goes to the login page and uses Forgot Password —
   the reset email doubles as proof of mailbox control at first use.
   When the society wants to announce the feature, a CR-005 segment
   email is the mechanism.
7. **Authorization lives in the app, not in Keycloak lifecycle.** The
   self-serve endpoints check DB state per request (subject → linked
   person → current MEMBER row → household's memberships). Nobody
   disables Keycloak accounts on lapse or household exit; a stale
   account that logs in simply sees no payable membership.
   Register→Keycloak sync is creation-time only in v1 — a later email
   edit does not propagate (known limitation, revisit if it bites).

## Approach

### Schema (V6 migration)

Reserved in the schema doc's "Known later additions":

```sql
ALTER TABLE person ADD COLUMN keycloak_subject text UNIQUE;
```

Nullable (most people never get an account), unique (one person per
account — Postgres UNIQUE permits many NULLs). No table for
provisioning runs: the report is returned to the caller, and the
durable state is the column itself plus the Keycloak accounts.

The "at most one household per email" rule (principle 4) stays an
application-level validation in provisioning, not a constraint —
`email_address` deliberately allows shared addresses, and the
email→household path runs through `household_person` history, which a
unique index can't express.

### Provisioning — `POST /api/admin/self-serve/provision`

Admin-triggered, batch, **idempotent** — safe to run after CR-002
import, after a CR-010 walk-in, after a CR-007 approval, or on a whim.
Deliberately NOT wired into `ImportService`/`AdminNewMemberResource`:
those own DB transactions, and a Keycloak REST call does not belong
inside one (it can't roll back). The admin runs provisioning as a
follow-up step; re-running heals any partial failure.

Candidate selection: every person with a current (`left_household_date
IS NULL`) `relationship_type` MEMBER row in an ACTIVE household, not
deceased, with a current email address (person's primary address, else
lowest email id). Then per candidate, first match wins:

| condition | action / report |
|---|---|
| `keycloak_subject` already set | `ALREADY_LINKED` (no-op) |
| email also carried by a candidate in a **different** household | `CONFLICT_HOUSEHOLDS` — principle 4; skipped, listed for the admin to fix the data |
| email shared within the household and this person is not the attributed one (primary contact wins, else lower person id) | `SHARED_ADDRESS` — skipped; the address's account belongs to the attributed person |
| a Keycloak user with this email exists, `emailVerified: true` | `ADOPT` — link its subject. Refuse (`CONFLICT_SUBJECT`) if that subject is already linked to a different person |
| a Keycloak user with this email exists, `emailVerified: false` | `SKIPPED_UNVERIFIED` — never link an unconfirmed mailbox claim (someone may have self-registered with an address they don't control); listed for the admin |
| otherwise | `CREATE` — new Keycloak user: username = email, `emailVerified: true` (principle 2), enabled, firstName/lastName from the person (**the `lastName` bite**: REST-created users missing user-profile required fields fail login with "Account is not fully set up"), **no credentials** |

For both `CREATE` and `ADOPT`, provisioning then sets `claimed_role =
member` via the claim mechanism (attribute + `syncClaim`, exactly the
`MeResource` path — never a bare role grant, which reconciliation would
revert) and finally `role_verified = true` (after the sync, which
resets it): the register vouching for the person IS verification. Then
the DB write: `person.keycloak_subject = <subject>`. Keycloak first,
DB second — if the run dies between, the next run's `ADOPT` branch
heals it.

`POST /api/admin/self-serve/preview` runs the same resolution and
returns the same report without touching anything (the import-preview
pattern). Both `@RolesAllowed("admin")`. Report shape: per-candidate
rows `{personId, name, email, action}` plus counts per action.

New `KeycloakAdmin` methods: exact-email lookup
(`GET /users?email=…&exact=true`) and user creation — the
`server-service` account's existing `manage-users` covers both.

Unlink: `DELETE /api/admin/people/{id}/keycloak-link` nulls the column
(email reassigned, wrong link, member asks). The Keycloak account is
left alone (principle 7); an unlinked account can log in and sees
"no membership linked".

### First login — silent + forgot-password

Created accounts have no credential. The member's first login is:
login page → "Forgot Password?" → Keycloak emails a reset link (to the
verified address) → set password → in. This needs two realm changes
(`server/keycloak/memberroll-realm.json` currently has both off):

- `"resetPasswordAllowed": true`
- `"verifyEmail": true` (principle 1 — applies to self-registered
  accounts; provisioned ones are already verified so see no prompt)

and a realm `smtpServer` block so Keycloak can send its own mail —
this is separate from the app's `Mail`/SMTP env. Dev: the compose
Mailpit, addressed by service name from inside the compose network
(`host: mailpit, port: 1025`, not the 18026 host mapping). Production:
the real relay, configured in the prod realm and mirrored back into
`server/keycloak/` per the dev/prod realm discipline (from-address/
SPF/DKIM alignment is CR-008's item). Realm-JSON edits observe the
standing gotcha: **no `clientScopes` key may creep in**.

No `UPDATE_PASSWORD` required action, no execute-actions email at
provisioning time: the reset flow already forces choosing a password,
and silence-until-opt-in was the explicit decision (principle 6).

### Member API

On `MeResource` (`/api/me`), authenticated but **no `@RolesAllowed`** —
the link is the authority, not the role (see "Role model" below).
Guest → 401 (the existing principal check).

| method | path | behaviour |
|---|---|---|
| GET | `/api/me/membership` | `{linked: false}` when no person carries the caller's subject (any authenticated account may ask; reveals nothing about others). Linked: `{linked: true, person: {givenName, familyName}, memberships: [...], history: [...]}` — `memberships` is the payable/current set per the `lostLinkRows` criteria (current households, non-CEASED, period current or renewal-open), each row carrying displayName, periodName, typeName, status, amountDueCents, amountPaidCents, paid; `history` is past periods' rows (period, type, status only) — read-only record, cheap, and the first thing a member asks ("was I financial last year?") |
| POST | `/api/me/membership/{id}/pay-link` | 404 unless that membership belongs to a household where the linked person has a current MEMBER row (same shape as the GET's criteria — a person removed from the household loses pay access with no Keycloak action, principle 7). Mints via `RenewalTokenStore.mint` (fresh-per-call, CR-004 amendment) and returns `{url}` = `PayResource.payUrl(token)` |

Pay-now is a **handoff, not a second payment path**: the browser
navigates to the returned `/web/pay.html?t=…` and everything from
there — line items, Checkout, webhook, receipt — is CR-004's one
Stripe surface, unchanged.

### Web UI

`web/index.html` becomes the "my membership" page — and this is the
long-promised retirement of the placeholder: **`NotesResource`,
`NoteStore` and the notes UI are deleted** (and `MEMBERROLL_DATA` with
them if nothing else reads it; their verify-matrix rows go too).

- Same `Auth` IIFE login as today (bearer + PKCE; the `shared/sha256.js`
  ordering and issuer/LAN gotchas now matter for *members'* phones).
- Logged in + linked: household display name, each current membership
  with status badge and amounts (Pico baseline, CR-009 patterns), a
  **Pay now** button per unpaid membership → POST pay-link → navigate
  to the pay page; the compact history table below.
- Logged in, not linked: "We couldn't find a membership linked to this
  account — contact <society name>." Deliberately **no lookup form**:
  a lookup would be an enumeration oracle. (The pay page's lost-link
  flow, always-202, remains the self-service recovery for pay links.)
- The mandatory claim modal behaves as today; provisioned accounts
  arrive with `claimed_role=member` so it never pops for them.

Admin UI: a "Self-serve" section on `admin/users.html` (the
Keycloak-facing page): Preview table (the report), Provision button,
counts. In `index.html`'s person detail: a linked/not-linked indicator
and an Unlink button.

### Role model — the second open question, resolved

The self-claimed `member` role keeps meaning "what the user says";
the `person.keycloak_subject` link means "what the register says", and
**the link is what gates self-serve** — `@RolesAllowed` is not used on
the member endpoints. Provisioned accounts get `claimed_role=member` +
`role_verified=true` through the claim mechanism purely so the
existing UX (claim modal, admin users page) tells the truth; nothing
authorizes off it. The claim machinery is otherwise untouched.

### Config

No new server env. `PUBLIC_BASE_URL`, Stripe and mail config all
arrived in CR-004/005. The realm JSON changes above are the only
configuration surface.

### Rejected alternatives

- **Email match at login + admin confirmation queue** (the roadmap's
  working assumption) — inference where provisioning gives certainty:
  shared addresses make matches ambiguous, a confirmation queue is a
  whole admin UI, an unverified self-registration must not even
  *propose* a match, and the flow is an enumeration surface. Pushing
  identity register→Keycloak removes the whole class of problem — and
  needs no email claim parsing in `AuthFilter` at all.
- **Invite / execute-actions email at provisioning** — decided
  against (principle 6): silent + forgot-password. ~100 unsolicited
  "you have an account" mails is spam; opt-in members find the login
  page; the announcement channel, when wanted, is CR-005.
- **Provisioning inline in import / new-member / approval flows** —
  a Keycloak REST call inside a JDBI transaction can't roll back;
  a separate idempotent batch is simpler and covers every source of
  new members with one code path.
- **Linking any household person (PARTNER pays the bills)** — MEMBER
  only (principle 5), consistent with the voting-rights and
  segment-email decisions. The PARTNER-managed household's recourse is
  the magic link in the renewal email, which needs no account.
- **Disabling Keycloak accounts on lapse/leave** — a sync obligation
  with no payoff; per-request DB checks already withdraw access
  (principle 7).
- **Gating member endpoints with `@RolesAllowed("member")`** — the
  role is self-claimed and would double-key authorization; the link
  is singular and register-backed.
- **A "request access" form for unlinked accounts** — an enumeration
  oracle; "contact the society" is the v1 answer.
- **Contact-detail editing** — roadmap out-of-scope for v1 (view +
  pay only).

## Verification plan

Scripted (extend `verify-matrix.sh` with CR6-* rows; fixtures under
`tmp/cr006-fixtures/`; the notes rows are *removed* alongside
`NotesResource`). Fixture households, seeded via API: **E** — two
MEMBER adults sharing one address (attribution case; primary contact
wins); **F** — MEMBER with own email + PARTNER with own distinct email
(exclusion case); **G** — MEMBER with no email; **H1/H2** — the same
address on MEMBERs in two different households (conflict case); **J**
— a MEMBER whose email is `testuser@example.invalid` (adopt case).
All with current-period memberships.

| # | caller | call | expect |
|---|---|---|---|
| 1 | guest / testuser / test-cli-noaud | POST /api/admin/self-serve/preview | 401 / 403 / 401 |
| 2 | testadmin | POST preview | 200; E → one CREATE for the primary contact, other sharer SHARED_ADDRESS; F MEMBER CREATE, F PARTNER **absent entirely**; G absent (no email — not a candidate row, unlike CR-005's NO_EMAIL which is about send coverage); H1+H2 both CONFLICT_HOUSEHOLDS; J ADOPT naming testuser |
| 3 | testadmin | POST provision | 200, actions match #2; psql: `keycloak_subject` set for E-primary, F-member, J and **only** them |
| 4 | testadmin | GET /api/admin/users?search=… | created accounts exist with claimed_role=member, verified=true, member role granted; testuser's claim now member/verified (adopt path) |
| 5 | testadmin | POST provision again | every previously-actioned row ALREADY_LINKED; Keycloak user count unchanged (idempotent) |
| 6 | testuser (now adopted = J) | GET /api/me/membership | 200 linked:true, J's household, correct period/type/amounts |
| 7 | testviewer / guest | GET /api/me/membership | 200 linked:false / 401 |
| 8 | testuser | POST /api/me/membership/{J's}/pay-link | 200 {url}; extract `t` → guest GET /api/pay/{t} → 200, same membership (the CR-004 surface end-to-end) |
| 9 | testuser | POST pay-link for household E's membership id | 404 (not their household) |
| 10 | — | REST-create a Keycloak user with G's (newly added) email, emailVerified=false; testadmin POST preview | G listed SKIPPED_UNVERIFIED, not linked |
| 11 | testadmin | DELETE /api/admin/people/{J}/keycloak-link | 200; testuser GET /api/me/membership → linked:false; re-provision re-adopts (subject unchanged) |
| 12 | — | psql | `\d person` shows keycloak_subject with a unique constraint; inserting a duplicate subject fails |
| 13 | any | GET /api/notes | 404 — retired with this CR |
| 14 | — | Keycloak login page HTML | contains the Forgot Password link (resetPasswordAllowed took) |
| 15 | — | trigger a reset-credentials email for a provisioned account (admin REST execute-actions, used here only as a relay probe) | the mail lands in Mailpit — proves the realm smtpServer block works |

Browser walkthrough (dev stack):

1. Seed fixtures, run Preview then Provision from the new users-page
   section; check the report rendering.
2. As F's member: login page → Forgot Password → reset mail in Mailpit
   → set password → land back in the webapp → my-membership shows the
   household, status badge, amount due; **no** verify-email prompt
   (pre-verified), **no** claim modal (pre-claimed).
3. Pay now → the CR-004 pay page with the right amounts; through to a
   test-card payment if a Stripe key is on hand; back on my-membership
   the status reflects payment.
4. Self-register a fresh account → Keycloak demands email verification
   (Mailpit round trip) → login → the not-linked message (and no way
   to probe the register).
5. Phone on the LAN IP: full login → my-membership → pay-link
   navigation (the issuer-allowlist and sha256-fallback gotchas now
   face members, not just admins).

Realm JSON changed but no Caddy/compose/auth-code changes; run the
deploy Local smoke (server/deploy/README.md §6) before the next
production push per the standing rule, since auth realm config moved.

## Notes for the implementing session

- Keycloak-before-DB ordering in provisioning is what makes re-runs
  the recovery story — don't "optimize" it into one loop that writes
  the DB first.
- The provision report is the admin's data-quality tool: resist
  collapsing CONFLICT_HOUSEHOLDS/SKIPPED_UNVERIFIED into a generic
  "skipped" — each names a different fix.
- Deleting NotesResource touches `ApiApplication`, `web/index.html`,
  `web/app.js`, the matrix, README/GETTING-STARTED, and CLAUDE.md's
  architecture paragraph — sweep for `MEMBERROLL_DATA` before removing
  the env var from docs.
- CR-010 precedent: do a headless-browser pass on the new
  my-membership page and the users-page provisioning section, not just
  the API matrix (the claim-modal-suppression and not-linked states
  are UI-only behaviours).

## Open questions

- None blocking. Deferred by design: register→Keycloak email-edit sync
  (creation-time only in v1); whether CR-007 approval should end by
  auto-running provisioning (v1: the admin presses the button);
  announcement wording/timing for the society (a CR-005 send, whenever
  they want).

## Results

Implemented 2026-07-18. What landed, mapped to the approach above:

- **V6 migration** `person.keycloak_subject text UNIQUE` (nullable).
- **`SelfServeStore`** — candidate selection (current MEMBER row, ACTIVE
  household, not deceased, primary-else-lowest email via LATERAL), the
  link column accessors, and the my-membership queries. The payable
  window is one SQL fragment (`PAYABLE_SQL`) shared by the GET, the
  history query (negated) and the pay-link guard, mirroring
  `lostLinkRows`.
- **`AdminSelfServeResource`** — `/preview` + `/provision`, one shared
  resolution pass implementing the first-match-wins table; provision
  executes CREATE/ADOPT per candidate: Keycloak account → claim
  attribute → `syncClaim` → `role_verified=true` (after the sync resets
  it) → DB link, in that order. A per-candidate Keycloak failure is
  reported as an `ERROR` row and does not sink the batch (re-run heals).
  `KeycloakAdmin` gained `findUsersByEmail` (exact) and `createUser`
  (reads the 201 Location header for the subject; blank-name guard for
  the `lastName` bite).
- **`MeResource`** — `GET /api/me/membership`,
  `POST /api/me/membership/{id}/pay-link`; no `@RolesAllowed`; the
  response also carries `societyName` so the not-linked page can name
  who to contact. Unlink + link-status ride `AdminPeopleResource`
  (`GET`/`DELETE /{id}/keycloak-link`).
- **Web** — `web/index.html`/`app.js` rewritten as the my-membership
  page (cards + status badges + Pay now handoff + history table;
  not-linked message; claim modal wiring unchanged).
  `NotesResource`/`NoteStore`/notes UI **deleted**; docs swept
  (`MEMBERROLL_DATA` gone). Admin: "Self-serve provisioning" section on
  users.html (preview/provision + report table), link indicator +
  Unlink in the person form.
- **Realm** — `verifyEmail: true`, `resetPasswordAllowed: true`,
  `smtpServer` → compose-internal `mailpit:1025`. No `clientScopes` key.

### Scripted matrix (2026-07-18)

`server/verify-matrix.sh` extended with the CR6-* rows (notes rows
10–22 removed; retirement asserted as CR6-13). Full run against the
fresh dev stack (compose down/up + cargo with the CR-004/005 env):
**PASS=428 FAIL=0** (CR4-09 checkout rows skipped — no Stripe key in
the shell), covering all 15 planned rows: auth (403/403/401), the
preview/provision report per fixture (E attribution CREATE +
SHARED_ADDRESS, F PARTNER absent, G absent, H1+H2 CONFLICT_HOUSEHOLDS,
J ADOPT of testuser), Keycloak-side state (claim=member, verified,
member granted; testuser adopted), idempotency (ALREADY_LINKED, user
count unchanged), the member view + amounts, pay-link end-to-end
through the CR-004 pay view, foreign-membership 404,
SKIPPED_UNVERIFIED (unverified account seeded via the bootstrap
admin), unlink → linked:false → re-adopt (same subject), the V6 unique
constraint (duplicate refused), the Forgot Password link on the login
page (the probe needs a PKCE challenge — the `web` client mandates
S256), and the realm smtpServer relay probe (reset mail in Mailpit).
Two matrix rows needed re-run hygiene: testuser's subject is unlinked
and prior runs' `testuser@example.invalid` email rows deleted at block
start, or the fixed adopt email correctly trips CONFLICT_SUBJECT /
CONFLICT_HOUSEHOLDS against earlier runs' fixtures.

### Browser walkthrough (headless, 2026-07-18)

Playwright/chromium, 16/16 checks: (A) admin users page — Preview
renders the report (fixture listed as Create account), Provision
gated on preview, applies, report + users list update; person form
shows "Linked to Keycloak account …" + Unlink. (B) provisioned
member's first login: login page → Forgot Password → reset mail in
Mailpit → set password → **lands back in the webapp logged in** —
no verify-email prompt (pre-verified), **no claim modal**
(pre-claimed); my-membership shows household, Unpaid badge, due
$45.00; Pay now navigates to the CR-004 pay page with the same
amount. (C) testviewer (unlinked) sees the not-linked message and no
way to probe the register. (D) fresh self-registration → Keycloak
demands email verification (Mailpit round trip) → verified login →
not-linked message. Not exercised: a physical phone on the LAN IP
(step 5) — manual, unchanged auth plumbing.

Stripe test-card payment (walkthrough step 3's tail) not exercised —
no `STRIPE_SECRET_KEY` on hand this session; the webhook → recompute
path is CR-004's surface, unchanged and still covered by its matrix
rows.

**Before the next production push**: run the deploy Local smoke
(deploy/README.md §6 — realm auth config changed), and mirror the
three realm changes (verifyEmail, resetPasswordAllowed, a production
`smtpServer` block pointing at the real relay) into the prod realm via
console/REST per the dev/prod realm discipline. From-address SPF/DKIM
alignment remains CR-008.

## Follow-ups / amendments

(dated additions after field feedback)
