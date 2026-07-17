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
mvn -pl server cargo:run       # dev Tomcat 10.1: http://localhost:8080/server/api/health (Ctrl-C stops)
(cd server && docker compose up -d)   # dev Keycloak :8081, realm imported from server/keycloak/
(cd server && docker compose down)    # discard Keycloak state; next up re-imports the realm

server/verify-matrix.sh         # the 48-case role x endpoint matrix against the running
                                # dev stack (ports via PORT / KEYCLOAK_PORT); extend it
                                # alongside new endpoints — it must stay green

# token for scripted verification (dev realm only)
curl -s -X POST http://localhost:8081/realms/memberroll/protocol/openid-connect/token \
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
panel; `manager` is grant-only; `admin` is console-only. Storage is
per-owner JSON files under `MEMBERROLL_DATA` (default `~/memberroll-server/`)
written atomically — add Postgres only when cross-user queries appear.
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
- **Two cargo Tomcats on one machine share more than port 8080**: a
  second instance needs `-Dcargo.servlet.port` AND `-Dcargo.rmi.port`
  AND `-Dcargo.tomcat.ajp.port` overridden — with only the servlet port
  changed, cargo finds the default RMI/shutdown port 8205 busy and
  SHUTS DOWN the other project's running Tomcat through it.
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
