# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working
with code in this repository.

## What this is

A Tomcat/Jersey webapp with Keycloak-backed identity, instantiated from
webapp-template (which was extracted from the TurbinePreview server).
One Maven module today: `server/`, a war (Jersey 3.1 / Tomcat 10.1,
jakarta namespace, Java 17). Identity lives entirely in Keycloak — the
server never sees a password, it only validates the RS256 bearer tokens
Keycloak issues (`AuthFilter`, JWKS-backed, multi-issuer allowlist).
Static pages in the same war (`web/` member page, `admin/` panel) share a
hand-rolled OAuth2 Authorization Code + PKCE login (`shared/auth.js` —
deliberately no keycloak-js). The `NotesResource`/`NoteStore` placeholder
was retired by CR-006 — `web/` is now the member "my membership" page.

## Commands

```bash
mvn clean package              # build server/target/server.war
mvn -pl server cargo:run       # dev Tomcat 10.1: http://localhost:18080/server/api/health (Ctrl-C stops)
(cd server && docker compose up -d)   # dev Keycloak :18081 + Postgres :5433 + Mailpit :18025 UI/:18026 SMTP
                                      # (project name "memberroll")
(cd server && docker compose down)    # discard dev state; next up re-imports realm, Flyway re-creates schema

# for the CR-004/CR-005 rows of the matrix, start cargo with the dev Stripe/mail env.
# Since CR-014 these SMTP_*/MAIL_* vars are the FALLBACK (the admin Mail settings
# page, when saved, takes precedence); with no page row saved they are the config,
# and the dev stack + verify-matrix rely on that ENV path (Mailpit at :18026).
# (STRIPE_SECRET_KEY optional — without it checkout answers 503, all else works;
# MAIL_REPLY_TO exercises CR-005's Reply-To header):
STRIPE_WEBHOOK_SECRET=whsec_devmatrix SMTP_HOST=localhost SMTP_PORT=18026 \
  MAIL_FROM=noreply@memberroll.dev MAIL_REPLY_TO=treasurer@memberroll.dev \
  MEMBERROLL_SOCIETY_NAME="MemberRoll Dev Society" mvn -pl server cargo:run

server/verify-matrix.sh         # the role x endpoint matrix (540 checks offline / +1 with a
                                # Stripe key) against the running dev stack (ports via
                                # PORT / KEYCLOAK_PORT); extend it alongside new endpoints —
                                # it must stay green. The CR-005 abort/resume rows stop and
                                # start the Mailpit container mid-run. Since CR-008 every
                                # environment coupling is env-overridable (ORIGIN, KC_BASE,
                                # KC_ADMIN_BASE, CURL_OPTS, RELAY_HOST/PORT, MAILPIT_*,
                                # POSTGRES_PORT/MEMBERROLL_DB_PASSWORD) so the SAME matrix
                                # runs against the deploy local smoke — the production-
                                # topology stand-in, since prod strips the test fixtures
                                # (invocation: server/deploy/README.md "Local smoke").

# token for scripted verification (dev realm only)
curl -s -X POST http://localhost:18081/realms/memberroll/protocol/openid-connect/token \
  -d grant_type=password -d client_id=test-cli -d username=testuser -d password=testuser \
  | jq -r .access_token

# production update loop (instance already provisioned per server/deploy/README.md)
server/deploy/push-war.sh <user@host>
```

Test identities: `testuser` (member), `testviewer` (no roles),
`testadmin` (admin) — password = username. `test-cli-noaud` mints
tokens without the server audience for negative tests.

There is no test suite. The verification convention: scripted curl
matrices against the running dev stack (mint tokens via `test-cli`,
assert exact HTTP statuses and JSON bodies for every role × endpoint
combination — `server/verify-matrix.sh` is the living baseline; extend
it with every new endpoint), plus a browser walkthrough for UI changes. Record the
matrix and its results in the change-request doc. Before a production
push, run the deploy "Local smoke" (server/deploy/README.md §6) when the
change touches auth, Caddy, or compose.

## Architecture in one paragraph

`ApiApplication` registers resources explicitly (no scanning) at
`/server/api`. `AuthFilter` runs first: no Authorization header → guest
(public endpoints keep working); an invalid token → 401; a valid one →
`UserPrincipal` (subject = the stable `sub`, the key for own-data;
realm roles; the `claimed_role`/`role_verified` attributes) and
`@RolesAllowed` enforcement via `RolesAllowedDynamicFeature`. Role
model: `member`/`other` are **self-claimed** (registration picker,
`PUT /api/me/claim`, or the webapp's mandatory modal); the server
reconciles claim → granted realm role on the fly (`KeycloakAdmin`, the
`server-service` service account); admins mark claims verified in the
panel; `manager` is grant-only; `admin` is console-only. The membership
register (CR-001, docs/membership_management_database_schema.md) lives
in Postgres: Flyway migrations under `server/src/main/resources/db/migration/`
are the schema's source of truth, the `Db` @WebListener (Hikari + Flyway,
fail-fast) exposes a shared JDBI instance, and hand-written SQL sits in
store classes (`PersonStore`, `HouseholdStore` — the pattern to copy).
CR-003 (renewals + manual payments) added `PeriodStore`/`MembershipStore`/
`PaymentStore` and the `AdminPeriods`/`AdminMemberships`/`AdminPayments`
resources over the same CR-001 tables (no schema change): their write
methods take an explicit `Handle` and the resource owns the transaction,
so a payment insert and the per-membership status recompute (paid-ness
derives from `payment_allocation`, rule 6) commit atomically. Corrections
are negative payments, never edits.
CR-004 added online payment without login: `RenewalTokenStore` mints
magic-link tokens (256-bit random, only the sha256 stored — so each mint
is a FRESH token and older unexpired links stay valid; expiry is the gate,
`used_at` is bookkeeping), the guest-reachable `PayResource` serves the
pay view / creates Stripe Checkout sessions (line items computed
server-side) / handles lost-link (always 202, no enumeration), and
`StripeWebhookResource` records payments (signature over the raw bytes is
its entire auth; one transaction; redeliveries hit the
`external_transaction_id` partial unique index and no-op; unprocessable
events are logged and answered 200). Positive STRIPE payments come only
from the webhook — the admin payments endpoint accepts STRIPE only with a
negative amount (recording a dashboard refund). `Mail` is the minimal
env-configured SMTP sender (receipts + lost-link; real templates are
CR-005), `web/pay.html`/`pay.js` is the public page (no auth.js — the
token IS the authorisation), and all of it is optional config: no Stripe
env → checkout/webhook answer 503, the app still starts.
CR-010 added the admin "new member" fast path: `AdminNewMemberResource`
(`POST /api/admin/new-member`) composes `PersonStore`/`HouseholdStore`/
`MembershipStore.createForHousehold` in one `jdbi.inTransaction` — every
failure path is a thrown `IllegalArgumentException`/`ConflictException`,
never an early-return `Response`, or JDBI would commit a half-created
member instead of rolling it back. `PersonStore.create`,
`HouseholdStore.create` and `HouseholdStore.addPerson` gained
Handle-taking overloads for this (the CR-003 signature-move pattern);
existing no-Handle callers delegate to them unchanged.
`MembershipStore.typeBounds` and `PeriodStore.Price`'s new
`minimumPeople`/`maximumPeople` fields expose `membership_type`'s
people-count columns (unused before this CR) so `admin/new-member.html`'s
wizard can mirror the server's rule client-side, without a dedicated
type-management endpoint (there is deliberately none): `maximumPeople`
counts only `relationship_type` MEMBER people (a PARTNER/DEPENDANT/OTHER
second person never counts against it — see the voting-rights paragraph
below), `minimumPeople` stays a total-headcount warning. The
second-person dialog must only pop from the `#nmType` change *event*,
never from initial page population — HOUSEHOLD sorts first
alphabetically and is therefore the type select's default on every
load, and popping a native `<dialog>` there steals modal focus (backing
inputs go inert) before the admin has typed anything.

CR-005 added segment email (templates, merge fields, send log,
communication preferences). A "segment" is not a new concept: it is the
CR-003 financial-status view (a period + the same status/type filters),
so `EmailStore.resolveSegment` targets exactly what the Renewals table
shows. Per membership it takes the household's **current MEMBER-relationship
people only** (PARTNER/DEPENDANT/OTHER never receive segment mail — the
voting-rights correction below), resolves each to a delivery method
(`CommunicationPreferenceStore`: person → household → the EMAIL default),
dedups EMAIL addresses within the membership (couples share one — primary
contact wins attribution, else lower person id), and records a `NO_EMAIL`
row for a membership that yields neither an address nor a skip. Merge
fields (`MergeFields`) are a CLOSED vocabulary validated STRICTLY at both
template save and send creation (an unknown `{{field}}` is a 400 — a
belt-and-braces guard against leaking a typo'd token into a hundred
mailboxes). `email_send` snapshots the composed subject/body (footer
included, tokens intact) so a later template edit never rewrites a past
send; `{{payLink}}` mints a fresh token per recipient at send time
(CR-004 amendment) and the `renewal_token_id` lands on the recipient row.
The sender runs SEQUENTIALLY on `Mail`'s single thread (no queue — the
2026-07-17 decision): `POST /api/admin/email/sends` writes the send +
all recipient rows in one transaction (409 if one is already RUNNING),
then `EmailStore.startSending` walks the PENDING rows; 5 consecutive
failures flip it to ABORTED (remaining rows stay PENDING), and
`.../resume` re-enqueues PENDING+FAILED (never SENT) — the recovery for
both a dead relay and a JVM restart mid-send. The saved footer is the
first `app_setting` row (`email_footer`), editable per-send with a
"save as default" checkbox that is just a `PUT .../email/footer`, not a
send parameter. `Mail` gained the optional `MAIL_REPLY_TO` header.
Preferences ride the person/household resources
(`GET`/`PUT .../preferences`), default EMAIL, and are written
insert-don't-overwrite (close the current row, insert the new — no churn
when the value already equals the inherited default).

CR-006 added member self-serve. Identity linking is **provision-time,
register-push** (never match-at-login): `POST /api/admin/self-serve/
provision` (with a write-nothing `/preview`) is an idempotent admin batch
that, for every current MEMBER-relationship person in an ACTIVE household
with an email (primary, else lowest id), creates a Keycloak account
(username = email, `emailVerified: true` — imported addresses count as
verified, and the `lastName` bite applies) or adopts an existing
**verified** one, sets `claimed_role=member` through the claim mechanism
(attribute + `syncClaim`, then `role_verified=true` AFTER the sync resets
it), and only then writes `person.keycloak_subject` (V6, nullable+unique)
— Keycloak first, DB second, so a re-run's ADOPT branch heals a crash
between the two; don't reorder it. The report's distinctions matter:
CONFLICT_HOUSEHOLDS (one address in two households — principle 4, an
application-level rule, deliberately not a DB constraint),
SHARED_ADDRESS (within-household sharer who is not the CR-005-attributed
person), SKIPPED_UNVERIFIED (an unverified self-registration must never
be linked), CONFLICT_SUBJECT — each names a different admin fix.
`MeResource` gained `GET /api/me/membership` and `POST /api/me/
membership/{id}/pay-link`, both authenticated but **deliberately not
`@RolesAllowed`**: the subject→person link is the authority (the
self-claimed `member` role is only UX), every request re-derives
entitlement from current register state (current MEMBER row; the
CR-004 lostLinkRows window in `SelfServeStore.PAYABLE_SQL`), and pay-now
is a handoff to the CR-004 pay page — one Stripe surface. Unlinked
accounts get `{linked: false}` and the page shows "contact the society";
there is deliberately NO lookup/request-access form (enumeration
oracle). Provisioning is silent (no invite mail): first login is
Keycloak's Forgot Password against the pre-verified address, which the
realm now enables (`resetPasswordAllowed`, `verifyEmail`, and an
`smtpServer` block pointing at compose-internal `mailpit:1025` — dev
Keycloak sends its own mail, separate from the app's `Mail` env).
Nobody disables Keycloak accounts on lapse/leave, and unlink
(`DELETE /api/admin/people/{id}/keycloak-link`) leaves the account
alone — a stale login simply sees no membership.
CR-012 added on-demand payment receipts (constitution 38(3)(b), "if
requested" — never an auto-send). `Receipts` is the ONE renderer:
it composes from the RECORDED payment (`PaymentStore.find`), never
request-time inputs, so the counter/email/webhook copies are one
document; the payment id is the receipt number (insert-only + reverse-
never-edit keeps it stable). A negative payment renders as a "Refund
record" (deliberately a plain receipt, not a DGR donation receipt).
Two admin endpoints (`GET`/`POST /api/admin/payments/{id}/receipt`):
GET returns the header/line/total fields + canonical `text` +
`defaultTo` (payer's primary email, else the CR-005/CR-006-attributed
household address, else null); POST emails it (`{to?}`, 400 when no
address resolves, 503 when `Mail.enabled()` is false — the checkout
mirror, never a silent no-op), stateless (nothing written, re-send
re-composes — no receipt log in v1). `StripeWebhookResource`'s inline
builder was refactored onto `Receipts.render` (it captures the insert's
`paymentId` and renders from the committed row after the commit; only
the Checkout `customer_details` email stays Stripe-specific). UI: a
**Receipt…** button beside each payment's Reverse opens a dialog with
**Print** (a bare pop-up holding just the receipt text — built with
`textContent`, never innerHTML — then `window.print()`, no print-CSS
against the admin page) and **Email** (prefilled `defaultTo`, disabled
with a hint when the GET's `mailEnabled` is false — the CR-005 banner
pattern). One transactional mail via `Mail.sendAsync`, NOT the CR-005
segment machinery.
CR-013 added the committee register: `committee_appointment` (V7) records
office-bearers and ordinary members as **term-bounded appointments, AGM
to AGM** — the current-is-null idiom of `household_person` (a serving term
has null `ended_date`; office is a per-term column, so multi-term history
falls out for free). Keyed on `person_id`, not `membership_person` (an
appointment spans periods). `CommitteeStore.agmRoll` is the primary flow
and mirrors the CR-003 rollover under the CR-010 atomic discipline: in
ONE `jdbi.inTransaction` it closes every open appointment
(`ended_date = agmDate`) then inserts the new slate, and every rejection
(a duplicate singular office, a person given both president and
vice-president, an unknown person, an `agmDate` before a currently-serving
term) is a thrown `IllegalArgumentException`/`ConflictException`, never an
early-return `Response`, so a bad line rolls the whole roll back. A
missing office or a non-member appointee is a returned `warnings` entry,
not a block — the committee, not the app, is the authority on eligibility
(the soft-guard pattern, like CR-006's one-email-per-household). Two
partial unique indexes back it: `(person_id, office) WHERE ended_date IS
NULL` (no duplicate open office per person) and `office WHERE ended_date
IS NULL AND office <> 'ORDINARY'` (the four singular offices are
single-holder). **Corrections are edits/removals, not reversals**
(deliberately unlike payments — administrative reference data, not a
ledger): `AdminCommitteeResource` PUT fixes an appointment in place,
DELETE drops a bogus row. There is **deliberately no Keycloak `committee`
role** (no app surface only committee members may use; a role would be
dead weight and a drift risk — derive it from this register if such a
surface ever exists). `GET /api/admin/committee/contacts` is the seam
CR-007 will consume — current committee members' primary emails and the
current secretary specifically, for routing the application-referral
notice.

CR-014 made the SMTP relay admin-configurable. `Mail` resolves its settings
**per send, uncached**, in the order PAGE → ENV → NONE: `AdminMailSettingsResource`
(`/api/admin/mail-settings`, admin-only) writes ONE `app_setting` row
(`smtp_settings`, a JSON blob — settings change as a unit, so one atomic value
beats seven rows and no migration is earned), and when that row is absent the
CR-004 `SMTP_*`/`MAIL_*` env vars remain the fallback (dev, bootstrap, or an
operator who wants the secret out of the DB). `Mail.resolve()` returns a
`Settings` record + `source`; `enabled()` is `resolve().source != NONE`, so
every existing gate (checkout 503, CR-012 `mailEnabled`, CR-005 banner) reflects
the page for free. Per-use resolution is deliberate — a saved change applies to
the next message with no restart (a CR-005 send ABORTED on a dead relay is fixed
from the page and resumed). A DB read failure falls back to ENV (logged once).
The ENV path is **byte-for-byte the CR-004 behaviour** (starttls.enable ONLY,
no `.required`; port default 25; `noreply@localhost` from-default) — proven by
the whole mail suite staying green with an empty `app_setting` table; a PAGE
`STARTTLS` additionally sets `.required=true` (no silent cleartext downgrade).
The password is stored plaintext (single-host topology — app-layer encryption
changes no threat model, only adds a key to lose), NEVER returned (only
`passwordSet`) and NEVER logged; `POST .../test` is the ONE place a send error
reaches a human (verbatim SMTP reply — "535 5.7.139 …" is the product for an
admin debugging a tenant) and even there the password is scrubbed from the error
string. Re-save semantics: an absent `password` keeps the stored one, `""`
clears it — the host can change without retyping the secret. `admin/mail-
settings.html` adds the Microsoft 365 preset (a client-side form-filler — the
server knows no vendors). Keycloak's own forgot-password mail stays a separate
realm-config concern.

CR-015 added the reconciliation export (Xero-ready payment categorisation) and
is the FIRST and only writer of `payment.reconciliation_status` — the column
CR-001 provisioned in V1 and left unused. `ReconciliationStore` reads the
CR-001 `payment`/`payment_allocation` tables into one row per payment with the
allocation split projected into MEMBERSHIP/JOURNAL/DONATION/OTHER columns
(base rows materialised with `.list()` BEFORE the allocation query runs on the
same handle, then folded — a mapper-side-effect during a lazy `.forEach` does
NOT reliably mutate), and its `reconcile(handle,…)` is the bounded mark. The
same `Filter` (from/to/method/unreconciledOnly) drives export and mark, and
the `maxPaymentId` the export hands back bounds the mark so a payment recorded
between export and mark is never swept in unseen — flipping this operational-
state column is NOT a breach of the payment rows' insert-only discipline
(corrections stay negative payments). All on `AdminPaymentsResource` (the
payments surface, no new resource): the two reads
(`export/reconciliation.csv` with a labelled trailing summary block +
`export/reconciliation` JSON carrying `maxPaymentId`), `export/xero-journal.csv`
(the §3 clearing-account journal — clearing +gross debit vs each income −net
credit, so it BALANCES to 0 by construction; STRIPE forced regardless of the
method param; **409 until the account mapping is saved**, never a guessed
code), `GET`/`PUT xero-account-mapping` (one `xero_accounts` `app_setting`
JSON blob of five OPAQUE codes + tax rate, the CR-014 pattern exactly — absent
= journal feature dormant, plain CSV still works; `BAS Excluded` the GST-free
default), and `POST reconcile`. `XeroAccounts.read` returns empty unless all
five codes are present. UI: a Reconciliation card on the Renewals page
(preview → download CSV → mark, the Xero-journal button shown only once the
mapping is saved) plus a RECONCILED badge on the payment list. Deliberately
NO Xero API and NO unmark endpoint (a mis-mark's remedy is the filterless
export). The recorded follow-up — Stripe fee/payout-id capture — would let the
generated journal include its fee lines and §3's clearing account zero with no
hands.

CR-017 added the membership card — the CR-012 one-renderer lesson again.
`Cards` composes from CURRENT register state (never anything stored) and ONLY
for an ACTIVE membership: `Cards.compose(handle, membershipId, personId)` is
the gate — one SQL joining the ACTIVE membership × the CURRENT
MEMBER-relationship `household_person` × person × period × type, empty for
everything else (unknown, non-MEMBER, non-ACTIVE). It reads live household
composition, NOT the membership_person snapshot, so a PARTNER added after
creation is still refused (the voting-rights rule against live state, and the
snapshot could otherwise disagree). Empty reads as an indistinguishable 404,
like CR-006 pay-link. `png()` renders 1012 × 638 (credit-card 85.6 × 54 mm at
300 dpi) via Java2D from the bundled `card/DejaVuSans*.ttf` (loaded with
`Font.createFont` — NEVER `new Font("SansSerif")` on a headless server) with an
optional `card/logo.png` slot (present-or-absent, text-only when absent — a repo
asset, the fork-distribution model, deliberately not an upload page); `toJson()`
is the assertable companion so the PNG only needs a magic-bytes/size check. The
member endpoints (`GET`/`POST /api/me/membership/{id}/card{,/info,/email}`) are
authenticated but **deliberately not `@RolesAllowed`** (the subject→person link
is the authority, CR-006); the member `card/email` goes to the caller's OWN
primary email with NO `to` param (not an arbitrary-destination mailer). The
admin endpoints (`/api/admin/memberships/{id}/card/{personId}{,/info,/email}`,
admin-only) mirror CR-012's dialog — most members never Keycloak-link, so this
is the primary card surface. `Mail` gained an `Attachment` record + a
`sendAsync(…, attachment)` overload: the no-attachment path stays byte-for-byte
the CR-004/005/012 single-part message (proven by every prior mail row staying
green — notably CR12-06c, mail body == receipt text), an attachment switches to
multipart/mixed. The **bearer-auth bite** shapes the UI: a static page's `<img
src="/api/…">` sends no Authorization header and 401s, so every image use (the
member page `<img>`, the download `<a>`, the print pop-up, the admin dialog
preview) hangs off ONE blob URL from an authenticated `fetch`, and
`window.print()` fires only after the image's `load` event. Wallet passes were
rejected (two vendor integrations + an annually-expiring Apple cert — a volunteer
society's time bomb); the card is a low-stakes credential (no QR/validity check),
staleness self-evident from "Valid to <period end_date>".

CR-008 readied production (docs/change-requests/008-production-deployment.md,
go-live runbook included there): the deploy assets — frozen at CR-001 —
caught up with the app. Prod compose now passes `PUBLIC_BASE_URL`
(derived from `DOMAIN` in compose.yml, never a separate .env line),
`MEMBERROLL_SOCIETY_NAME` and the `STRIPE_*` pair (blank-is-unset:
empty = checkout/webhook 503) to Tomcat; there is deliberately NO
`SMTP_*` in the prod compose — the CR-014 page is production mail
config, and Keycloak's own relay is entered once via the tunnel console
(the one mirror-back exception; the render strips the dev `smtpServer`
block, keeping it only under `KEEP_TEST_FIXTURES=1`). `backup.sh` dumps
BOTH databases (memberroll = the financial record — it previously
dumped only keycloak); the `MEMBERROLL_DATA`/`data/store` vestige is
gone (nothing ever read it). The smoke override gained a `mailpit`
service (name matters — the realm's `smtpServer` host) plus loopback
Postgres/Mailpit publishes, so the FULL matrix runs against the smoke.

**Voting rights are MEMBER-only** (corrected 2026-07-18 — the earlier
"both adults vote" note had no recorded rationale and was wrong):
`MembershipStore.insertMembershipPerson` sets
`is_statutory_member`/`has_voting_rights`/`eligible_for_committee` true
only for `relationship_type` MEMBER; PARTNER/DEPENDANT/OTHER are covered
by the membership but never vote. This one method backs CR-002's import,
CR-003's rollover, and CR-010's new-member endpoint, so the rule is
uniform everywhere a membership's composition is copied — see
`docs/membership_management_database_schema.md` "Formal member status"
and `docs/ROADMAP.md` "Voting rights, corrected" for the record.
Production topology (server/deploy/): Caddy is the sole ingress and TLS
terminator, Tomcat serves the war, Keycloak runs in production mode on
Postgres under `/auth` with a single public issuer, and the admin
console is SSH-tunnel-only.

## Things that bite

- **Realm JSON must declare NO `clientScopes` key**: any `clientScopes`
  in an import suppresses ALL of Keycloak's built-in scopes (profile,
  roles, basic — which provides `sub`), breaking even Keycloak's own
  account console. Custom claims ride as per-client protocolMappers
  instead. If a token claim vanishes after a realm edit, check whether
  a `clientScopes` key crept back in.
- **Issuer = how the client reached Keycloak** (Keycloak 26). Phones on
  the LAN need the LAN IP in the `KEYCLOAK_ISSUER` comma-allowlist AND
  in the `web` client's redirectUris/webOrigins. A DHCP lease change
  silently re-breaks both. Fresh-login-then-401 = issuer mismatch; the
  webapp surfaces it via `Auth.onFresh401` instead of redirect-looping.
- **Dev vs prod realm discipline is opposite**: dev Keycloak has no
  volume — the checked-in realm IS the config and `down` re-imports it;
  production imported once and the DATABASE is authoritative — mirror
  console/REST changes back into `server/keycloak/`, and never wipe the
  prod database (subjects would be reminted, orphaning owner-keyed data).
- **`crypto.subtle` is secure-context-gated**: present on
  https/localhost, absent on `http://<LAN-IP>` — PKCE S256 would throw
  silently on phones. `shared/sha256.js` is the fallback; every page
  loads it BEFORE `auth.js`. No downgrade to `plain`, ever.
- **A killed cargo poisons its config dir**: if `cargo:run` dies
  abnormally, `rm -rf server/target/cargo` before the next run.
- **Recreating the dev DB under a running app leaves it schemaless**:
  `Db` runs Flyway once, at webapp start. `docker compose down && up`
  gives you a fresh empty Postgres, but a still-running `cargo:run`
  never re-migrates it — every query then fails with `relation ... does
  not exist`. Restart cargo after a compose down/up so Flyway re-creates
  the schema (V1) and seed data (V2) against the new database.
- **Compose infers the project name from the directory** (`server`), and
  two checkouts with compose files in same-named dirs silently RECREATE
  each other's containers on `up`. The dev compose pins `name:
  memberroll`; any new compose file needs its own `name:` too.
- **JDBI's SQL parser treats `\'` as an escaped quote** inside string
  literals, so `ESCAPE '\'` swallows the rest of the statement and named
  params reach Postgres raw (syntax error at ":"). Don't write `ESCAPE`
  clauses — backslash is already Postgres's default LIKE escape (the
  stores' escapeLike() relies on that).
- **DriverManager doesn't find JDBC drivers in the webapp classloader**
  under Tomcat: Hikari needs the explicit
  `setDriverClassName("org.postgresql.Driver")` that `Db` already has —
  keep it when copying that setup.
- **This project's dev stack deliberately lives on 18xxx ports**
  (Tomcat 18080 + RMI 18205 + AJP 18009 pinned in server/pom.xml's cargo
  config; Keycloak 18081; auth.js pins the browser side) so it coexists
  with other checkouts' dev stacks on the conventional 8080/8081. Two
  cargo Tomcats share more than the servlet port: with only that port
  changed, cargo finds the other's RMI/shutdown port busy and SHUTS
  DOWN the other project's running Tomcat through it — change the port
  scheme only as a full set, in all the places above at once.
- **Keycloak REST user creation needs `lastName`** (and the user-profile
  required fields generally) or logins fail with "Account is not fully
  set up".
- **`KeycloakAdmin` needs realm-management `view-realm`** in addition to
  view-users/manage-users — reading role definitions is not covered by
  the user permissions (fails only at first role grant, not at startup).
- **`[hidden] { display:none !important }` stays in every page's CSS**:
  an author `display:flex` on a modal otherwise beats the `hidden`
  attribute and an invisible overlay eats all taps.
- **Admin role changes inside the claimable set go through the claim**,
  never a direct grant — reconciliation would revert a grant that
  contradicts the `claimed_role` attribute. Grant-only roles (like
  `manager`) stay outside `KeycloakAdmin.CLAIMABLE` for exactly this
  reason.
- **Static pages can't be role-gated server-side** (bearer auth, no
  cookies): the admin panel gates itself client-side and every write API
  is `@RolesAllowed` — keep both halves when adding admin features.
- **Behind Caddy, scheme/IP correctness is the RemoteIpValve** in
  `server/deploy/tomcat/context.xml` — don't drop it when touching the
  Tomcat image.
- **The JVM caches negative DNS for 10s**: while a compose-internal
  hostname's container is down, lookups fail AND the failure is cached —
  sends to a just-restarted `mailpit` fail instantly (not a timeout) for
  up to 10s after it's back. The matrix's abort/resume rows sleep this
  window out for named relays; dev (`localhost`) never sees it. Same
  physics applies to any in-network hostname in production.

## Workflow conventions

- Substantial features follow the change-request pattern: write
  `docs/change-requests/NNN-name.md` first (approach, design,
  verification plan), implement, run the verifications, record results
  in the doc, and keep README/GETTING-STARTED current.
- Code style: Java with explicit registration and plain JDK types (no
  ORM, no DI beyond what Jersey needs; JSON via jakarta.json); browser
  JS is framework-free classic scripts sharing the `Auth`/`Claim` IIFE
  pattern; comments explain constraints, not restatements.
- Scripted verification fixtures live under `tmp/<cr>-fixtures/`
  (gitignored) so a matrix can be re-run later.
- New dependencies are fine when they earn their keep; the war has no
  framework beyond Jersey by design — prefer copying the existing
  patterns over introducing abstraction layers.
