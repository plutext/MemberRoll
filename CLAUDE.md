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
Static pages in the same war (`web/` user page, `admin/` panel) share a
hand-rolled OAuth2 Authorization Code + PKCE login (`shared/auth.js` —
deliberately no keycloak-js). `NotesResource`/`NoteStore` is the worked
example of an owner-scoped resource over a filesystem store; it is
placeholder app content, made to be replaced.

## Commands

```bash
mvn clean package              # build server/target/server.war
mvn -pl server cargo:run       # dev Tomcat 10.1: http://localhost:18080/server/api/health (Ctrl-C stops)
(cd server && docker compose up -d)   # dev Keycloak :18081 + Postgres :5433 + Mailpit :18025 UI/:18026 SMTP
                                      # (project name "memberroll")
(cd server && docker compose down)    # discard dev state; next up re-imports realm, Flyway re-creates schema

# for the CR-004 rows of the matrix, start cargo with the dev Stripe/mail env
# (STRIPE_SECRET_KEY optional — without it checkout answers 503, all else works):
STRIPE_WEBHOOK_SECRET=whsec_devmatrix SMTP_HOST=localhost SMTP_PORT=18026 \
  MAIL_FROM=noreply@memberroll.dev mvn -pl server cargo:run

server/verify-matrix.sh         # the role x endpoint matrix (266 checks with a Stripe key,
                                # 265 offline) against the running
                                # dev stack (ports via PORT / KEYCLOAK_PORT); extend it
                                # alongside new endpoints — it must stay green

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
`NoteStore`'s per-owner JSON files under `MEMBERROLL_DATA` remain the
worked example for single-owner blobs, slated for retirement.
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
