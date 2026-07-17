# webapp-template

A project template for a Tomcat/Jersey/Keycloak webapp with a working
login story, an admin panel, a worked own-data CRUD example, and a
single-box production deployment (Caddy TLS, push-war update loop).
Extracted from the TurbinePreview server (CRs 026–041, battle-tested in
production); the app-specific parts were replaced by a small "notes"
example that demonstrates every pattern.

## Start a new project

```bash
git clone <this-repo> myproject && cd myproject
./init.sh myproject com.acme.myproject "My Project"
```

`init.sh` rewrites every placeholder (`myapp`, `com.example.myapp`,
`MYAPP_`, the realm and audience names), re-initializes git history, and
deletes itself. Then read [docs/GETTING-STARTED.md](docs/GETTING-STARTED.md).

## What you get

- **`server/`** — a Maven-built war (Jersey 3.1 / Tomcat 10.1, Java 17):
  bearer-token auth against Keycloak's JWKS (multi-issuer dev allowlist),
  `@RolesAllowed` enforcement, a self-claimed-role + admin-verification
  workflow, a Keycloak admin REST client, and `NotesResource`/`NoteStore`
  as the copy-me example of an owner-scoped resource.
- **`server/src/main/webapp/`** — no-framework browser UI: a shared
  hand-rolled OAuth2 PKCE login (`shared/auth.js`, with a plain-JS SHA-256
  fallback for insecure LAN contexts), a user page (`web/`), and an admin
  panel (`admin/`) with a Keycloak users section.
- **`server/keycloak/`** — the realm as checked-in config
  (roles, clients, test users, registration role picker), imported fresh
  on every dev `docker compose up`.
- **`server/deploy/`** — the production story: one instance, Caddy TLS in
  front of Tomcat + Keycloak (production mode, on Postgres),
  `push-war.sh` update loop, nightly backups, and a full local smoke of
  the production topology.
- **`docs/`** — [GETTING-STARTED.md](docs/GETTING-STARTED.md) for you,
  [CLAUDE.md](CLAUDE.md) for Claude Code, and the change-request
  workflow under [docs/change-requests/](docs/change-requests/).

## Dev loop (after init)

```bash
mvn clean package                  # build the war
cd server && docker compose up -d  # Keycloak on :8081 (realm auto-imported)
cd .. && mvn -pl server cargo:run  # Tomcat on :8080 (Ctrl-C stops)
# http://localhost:8080/server/web/   — log in as testuser / testuser
# http://localhost:8080/server/admin/ — log in as testadmin / testadmin
```
