# Getting started

This walks you (the human) from a fresh clone to a running dev stack and
a production deployment. Claude Code's orientation lives in
[../CLAUDE.md](../CLAUDE.md) — keep both updated as the project grows.

## 0. Prerequisites

- JDK 17+ and Maven
- Docker with the compose v2 plugin (`docker compose`, not `docker-compose`)
- `curl` and `jq` for the verification snippets

## 1. Instantiate the template

```bash
git clone <template-repo> myproject && cd myproject
./init.sh myproject com.acme.myproject "My Project"
```

Arguments: project id (lowercase, used in artifact ids / realm / env
prefix / `/opt/<id>`), Java package, and an optional display name.
The script renames everything, re-initializes git (one fresh initial
commit), and removes itself. Everything below assumes the id `memberroll` —
yours will differ.

Decide licensing now: the template ships headerless with no LICENSE
file; add your organization's LICENSE and headers before the first push.

## 2. Dev loop

```bash
mvn clean package                  # builds server/target/server.war
cd server && docker compose up -d  # Keycloak :8081, realm imported from server/keycloak/
cd .. && mvn -pl server cargo:run  # Tomcat :8080, war deployed at /server
```

Smoke:

```bash
curl http://localhost:8080/server/api/health        # {"status":"ok"}
TOKEN=$(curl -s -X POST http://localhost:8081/realms/memberroll/protocol/openid-connect/token \
  -d grant_type=password -d client_id=test-cli \
  -d username=testuser -d password=testuser | jq -r .access_token)
curl -s http://localhost:8080/server/api/whoami -H "Authorization: Bearer $TOKEN"
```

Browse:

- <http://localhost:8080/server/web/> — the user page (notes example).
  Log in as `testuser` / `testuser`, or click through Keycloak's
  **Register** link to create a real account (the registration form
  includes the "I am a …" role picker).
- <http://localhost:8080/server/admin/> — the admin panel
  (`testadmin` / `testadmin`): user list, claim correction, verified
  flag, manager grant.
- <http://localhost:8081/> — the Keycloak admin console
  (`admin` / `admin`): look, but put permanent changes in
  `server/keycloak/memberroll-realm.json` — a `docker compose down` discards
  console edits in dev.

Test identities (dev realm only — `deploy.sh` strips them from
production): `testuser`/`testuser` (member), `testviewer`/`testviewer`
(no roles), `testadmin`/`testadmin` (admin). `test-cli` enables the
password grant for scripts; `test-cli-noaud` mints tokens *without* the
server audience so you can prove the server rejects them.

## 3. Phones on the LAN

`localhost` doesn't exist on a phone, and Keycloak 26 stamps tokens with
the issuer **as the client saw it**, so the war must allowlist both:

```bash
IP=$(ip route get 1.1.1.1 | grep -oP 'src \K[0-9.]+')
KEYCLOAK_ISSUER="http://localhost:8081/realms/memberroll,http://$IP:8081/realms/memberroll" \
    mvn -pl server cargo:run
```

Also add `http://$IP:8080/*` to the `web` client's `redirectUris` and
`webOrigins` in the realm JSON (and restart Keycloak), or Keycloak
answers "Invalid parameter: redirect_uri" on the phone. Note a DHCP
lease change breaks both places — recheck the IP first when LAN login
suddenly fails.

## 4. Adding your app

The worked example is the pattern:

- **New endpoint**: copy `NotesResource` (guest 401 challenge, owner
  scoping via the token's `sub`, admin override, id validation) and
  register it in `ApiApplication.getClasses()`.
- **New storage**: copy `NoteStore` (env-configured root, atomic writes,
  id pattern = path-traversal guard). Reach for Postgres only when you
  need cross-user queries.
- **New page**: copy `web/` (boot sequence in `app.js`; every fetch via
  `Auth.api`). Keep `[hidden]{display:none !important}` in the CSS.
- **New role**: claimable → `KeycloakAdmin.CLAIMABLE` + realm JSON
  (role, user-profile options) + `shared/claim.js` labels + admin.js
  `CLAIMABLE`; admin-granted → follow `manager` in
  `AdminUsersResource`/admin.js.

Workflow convention: substantial changes start as a change-request doc —
see [change-requests/](change-requests/).

## 5. Production

Follow [../server/deploy/README.md](../server/deploy/README.md):
provision an instance once, then the update loop is

```bash
mvn clean package
server/deploy/push-war.sh ubuntu@<instance>
```

Before the first real deployment, rehearse locally: the "Local smoke"
section of that README runs the exact production topology (Caddy TLS,
Keycloak-on-Postgres, single issuer) on your machine.

Production realm discipline — the one rule that prevents real pain: in
production the Keycloak **database** is authoritative (the realm JSON was
imported once, onto an empty database). Make changes via the tunnel-only
console or admin REST, then mirror them into
`server/keycloak/memberroll-realm.json`. Never wipe the production Keycloak
database: users' subject ids — the keys to all their data — would be
reminted.
