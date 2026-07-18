# memberroll server

A Tomcat/Jersey webapp with Keycloak-backed identity.

## Dev loop

```bash
mvn clean package                          # builds server/target/server.war
mvn -pl server cargo:run                   # Tomcat 10.1 on http://localhost:18080/server/api/...  (Ctrl-C stops)

cd server
docker compose up -d                       # Keycloak :18081 + Postgres :5433 (first run pulls images)
docker compose stop                        # pause, keeping state
docker compose down                        # remove; next `up` re-imports the realm fresh and
                                           # Flyway re-creates the app schema at Tomcat start

./verify-matrix.sh                         # the role x endpoint matrix against the running
                                           # stack (428 checks; PORT/KEYCLOAK_PORT env to retarget)
```

Endpoints: `/server/api/health` (public), `/server/api/whoami` (any
logged-in user), `/server/api/admin/ping` (admin role),
`/server/api/me/claim` (PUT, own role claim), `/server/api/admin/users`
(admin: list/claim/verify/manager), and member self-serve (CR-006):
`GET /server/api/me/membership` (any logged-in account — `linked: false`
when no `person` row carries the caller's Keycloak subject) and
`POST /server/api/me/membership/{id}/pay-link` (mints a CR-004 magic
link for a membership in the caller's own payable set). Provisioning
(admin): `POST /server/api/admin/self-serve/preview` / `.../provision`,
plus `GET`/`DELETE /server/api/admin/people/{id}/keycloak-link`.

The membership register (CR-001, admin role): `/server/api/admin/people`
(GET list `?q=&limit=&offset=`, POST) and `/{id}` (GET/PUT — emails and
phones ride inside the person payload; people are never deleted), and
`/server/api/admin/households` (GET/POST, `/{id}` GET/PUT,
`/{id}/people` POST, `/{id}/people/{personId}` DELETE = records a
leaving date, refuses the primary contact). Data lives in Postgres;
Flyway migrations under `server/src/main/resources/db/migration/` are
the schema's source of truth.

Server configuration (env, with dev defaults):
`KEYCLOAK_ISSUER=http://localhost:18081/realms/memberroll` (comma-separated
allowlist), `KEYCLOAK_AUDIENCE=memberroll-server`, `KEYCLOAK_ADMIN_URL`
(defaults to the first issuer's base), `KEYCLOAK_SERVICE_CLIENT` /
`KEYCLOAK_SERVICE_SECRET` (dev defaults match the checked-in realm),
`MEMBERROLL_DB_URL=jdbc:postgresql://localhost:5433/memberroll` /
`MEMBERROLL_DB_USER` / `MEMBERROLL_DB_PASSWORD` (dev default `memberroll`).

Payments + mail (CR-004) — all optional: with none of them set the app
still starts and runs manual-payments-only (checkout and webhook answer
503, mail sends are logged no-ops). A production deployment that wants
online payment sets all of these (live keys and the dashboard webhook
registration are CR-008):

| var | dev value | purpose |
|---|---|---|
| `STRIPE_SECRET_KEY` | `sk_test_…` (sandbox) | Checkout session creation; unset → checkout 503 |
| `STRIPE_WEBHOOK_SECRET` | the `whsec_…` from `stripe listen` (offline matrix: `whsec_devmatrix`) | webhook signature verification — the endpoint's entire auth; unset → webhook 503 |
| `PUBLIC_BASE_URL` | defaults to `http://localhost:18080/server` | base for pay links and Checkout return URLs; leaving it unset in production means emailed links point at localhost (a loud WARN is logged) |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` / `SMTP_STARTTLS` | `localhost` / `18026` (Mailpit), no auth | receipt + lost-link email; `SMTP_HOST` unset disables mail |
| `MAIL_FROM` | `noreply@memberroll.dev` | From address (SPF/DKIM for it is CR-008) |
| `MAIL_REPLY_TO` | unset (no Reply-To header) | CR-005: where renewal replies should land (the treasurer, not `noreply@`); unset keeps the prior behaviour |
| `MEMBERROLL_SOCIETY_NAME` | `memberroll dev` | email + pay-page branding (the single-tenant rule: no society name in code) |

## The webapps

- <http://localhost:18080/server/web/> — the member's "my membership"
  page (CR-006): log in (or register via the hosted Keycloak pages;
  provisioned members use Forgot Password the first time), see the
  household's membership status, and pay via the CR-004 pay page. An
  admin account gets a link to the panel.
- <http://localhost:18080/server/admin/> — the admin panel (`testadmin` /
  `testadmin`): the users section lists accounts with claimed role /
  verified flag / granted roles, corrects claims, records verification,
  and grants `manager`. Non-admins are bounced to the user page.

Both share the PKCE login in `shared/auth.js` and the Keycloak `web`
client; the login flow is exactly what a native mobile app (AppAuth)
would run.

## Device testing on the LAN

A phone cannot reach `localhost`, and Keycloak 26 stamps tokens with
the issuer **as the client saw it** — so for device testing everything
must agree on the dev machine's LAN IP:

```bash
IP=$(ip route get 1.1.1.1 | grep -oP 'src \K[0-9.]+')   # e.g. 192.168.1.50
KEYCLOAK_ISSUER="http://localhost:18081/realms/memberroll,http://$IP:18081/realms/memberroll" \
    mvn -pl server cargo:run
# Keycloak needs no change (docker compose up -d as usual), BUT the web
# client's redirectUris/webOrigins in server/keycloak/memberroll-realm.json
# must include http://$IP:18080 for browser logins from the phone.
```

`KEYCLOAK_ISSUER` is a comma-separated allowlist: with both entries, a
localhost browser and phones on the LAN work at the same time (each
token is validated strictly against the one issuer it names). If a
login fails server-side with 401s, the first suspect is an issuer
mismatch — compare the token's `iss` with Tomcat's allowlist; the pages
say so on screen instead of looping back to login.

## Production deployment

Lives in [deploy/](deploy/) — one instance, Caddy terminating TLS
(Let's Encrypt) in front of the war, Keycloak in production mode under
`https://<domain>/auth` (single issuer, users persist on Postgres), the
Keycloak admin console SSH-tunnel-only. `deploy/README.md` is the
provisioning document; `deploy/push-war.sh` is the update loop. The dev
loop above is unchanged — all of that directory's compose/config is
separate from `docker-compose.yml`. A full local rehearsal of the
production topology exists: `deploy/README.md`, "Local smoke".

## Keycloak for the unfamiliar

Keycloak owns all identity: registration, login, passwords, roles. The
server never sees a password — it only validates the signed tokens
Keycloak issues. **In dev, all configuration is the checked-in file**
`server/keycloak/memberroll-realm.json`, imported every time the container
starts fresh. If you change the realm in the admin console, your changes
last only until the next `docker compose down` — to make a change
permanent, put it in the JSON (or ask Claude to).

### How a real person registers (no server code involved)

1. Any OIDC login page for the realm offers **Register** — e.g. the
   user page's sign-in button, or for a quick look:
   <http://localhost:18081/realms/memberroll/account/>.
2. Fill in username/email/password and pick **"I am a …"**. Email
   verification and OTP are off by default.
3. The picked role is a **claim**: the server grants the matching realm
   role automatically on the user's first authenticated call, marked
   *unverified* until an admin checks it. A registration that skipped
   the picker has no app roles — the user page puts a **blocking,
   mandatory** claim form in front of claim-less accounts;
   `PUT /api/me/claim {"role": "member"}` sets it directly for scripts.

### Roles: who sets what

- **Self-claimed** (`member`, `other`): the registration picker, the
  webapp's mandatory form, or `PUT /api/me/claim`. Changing the claim
  swaps the granted role and resets the verified flag. The new role
  lands in the user's *next* token (the webapp forces a refresh so it
  is immediate there).
- **Verified**: the admin panel's users section — records that the
  claim was checked as fact.
- **`manager`**: nobody can claim it — the admin panel grants and
  revokes it.
- **`admin`**: the Keycloak console (Users → Role mapping) or the realm
  JSON — deliberately not grantable through the panel.
- The machinery is the `server-service` confidential client (service
  account: realm-management `view-users`, `manage-users`, `view-realm`
  — the last is what allows *reading role definitions*). A console
  *role grant* inside the claimable set gets reverted by the server's
  reconciliation when it contradicts the user's `claimed_role`
  attribute: correct the claim (admin panel), don't fight the grant.

### Test identities (dev realm only)

| username | password | realm role |
|---|---|---|
| `testuser` | `testuser` | member |
| `testviewer` | `testviewer` | *(none)* |
| `testadmin` | `testadmin` | admin |

`test-cli` is a dev-only client with the password grant enabled so
scripts can mint tokens:

```bash
curl -s -X POST http://localhost:18081/realms/memberroll/protocol/openid-connect/token \
  -d grant_type=password -d client_id=test-cli \
  -d username=testuser -d password=testuser | jq -r .access_token
```

`test-cli-noaud` exists only to prove the server rejects tokens minted
for other services (no `memberroll-server` audience). Neither belongs in a
production realm; nor do the test users — `deploy.sh` strips all of
them from the production render.
