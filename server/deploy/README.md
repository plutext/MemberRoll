# Production deployment

One instance serving the whole stack over HTTPS on one domain:

| URL | What |
|---|---|
| `https://<domain>/server/web/` | user webapp |
| `https://<domain>/server/admin/` | admin panel |
| `https://<domain>/server/api/…` | REST API |
| `https://<domain>/auth/…` | Keycloak (hosted login/registration, tokens, JWKS) |
| `http://localhost:8081/auth/admin/` | Keycloak admin console — **SSH tunnel only** |

The dev loop ([../README.md](../README.md)) is unchanged; this directory
is only about the instance.

## 1. Instance

- **Type**: something like `t4g.medium` (2 vCPU Graviton, 4 GiB) with a
  30 GiB gp3 root volume; `t3.medium` is the x86 fallback.
- **OS**: Ubuntu Server 24.04 LTS.
- **Security group**: 80 + 443 from `0.0.0.0/0` (80 stays open — ACME
  HTTP-01 and the https redirect), 22 from the admin's IP only. Nothing
  else — Postgres/Keycloak/Tomcat never publish to the network.
- **Elastic IP** associated, and an A record for the domain pointing at it.

One-time OS preparation (as root):

```bash
apt-get update && apt-get install -y docker.io docker-compose-v2 docker-buildx
systemctl enable --now docker
usermod -aG docker ubuntu        # takes effect on the NEXT login: log out/in
                                 # (or `newgrp docker`) before running deploy.sh,
                                 # else the docker socket answers "permission denied"

# 2 GiB swap: a burstable instance should degrade, not OOM-kill
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab

# Docker log rotation so logs can't fill the disk
cat > /etc/docker/daemon.json <<'EOF'
{ "log-driver": "json-file", "log-opts": { "max-size": "20m", "max-file": "5" } }
EOF
systemctl restart docker

# unattended security patches (Ubuntu enables this by default; confirm)
apt-get install -y unattended-upgrades

mkdir -p /opt/memberroll && chown ubuntu:ubuntu /opt/memberroll
```

## 2. First deploy

On the **dev machine**:

```bash
mvn clean package
server/deploy/push-war.sh ubuntu@<instance>
```

On the **instance**:

```bash
cd /opt/memberroll
./deploy.sh <domain>       # writes .env (generated secrets), renders the
                           # realm, builds the tomcat image, compose up
```

`.env` is the credential set (Keycloak bootstrap admin, DB passwords,
`server-service` secret, and — filled in at go-live, CR-008 runbook —
the live Stripe key + webhook signing secret and the society display
name) — mode 600, never leaves the box, back it up somewhere safe once.
Stripe values blank = checkout/webhook answer 503, everything else
works; after editing `.env`, `docker compose up -d` applies it.

Outbound mail is NOT configured here: the app's relay is the admin
**Mail settings page** (CR-014 — Exchange Online preset), and Keycloak's
own sender (forgot-password) is realm config entered once through the
tunnel console (section 3). The production realm imports with no
`smtpServer` — the checked-in block points at the dev Mailpit and is
stripped by the render.

While iterating on a fresh provision, point ACME at the **staging** CA to
stay clear of Let's Encrypt rate limits, then switch to production:

```bash
ACME_CA=https://acme-staging-v02.api.letsencrypt.org/directory docker compose up -d caddy
# ... iterate until https://<domain>/server/api/health answers (staging cert = browser warning) ...
docker compose stop caddy && sudo rm -rf data/caddy && docker compose up -d caddy   # real certificate
# (sudo: the container runs as root, so its cert storage is root-owned on the host)
```

First smoke, from anywhere:

```bash
curl https://<domain>/server/api/health
curl -s https://<domain>/auth/realms/memberroll/.well-known/openid-configuration | jq .issuer
#   → "https://<domain>/auth/realms/memberroll"   (the single issuer)
```

Then register your own account through the webapp and grant it the
`admin` role via the console (next section).

## 3. Keycloak admin console (tunnel-only)

The console and the master realm answer **403** on the public interface
(Caddyfile). Access:

```bash
ssh -N -L 8081:127.0.0.1:8081 ubuntu@<instance>
```

and browse <http://localhost:8081/auth/admin/> — credentials are
`KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD` from `.env`. (Same
address as the dev console; `KC_HOSTNAME_ADMIN` makes Keycloak serve the
console at the tunnel's URL while public traffic keeps the
`https://<domain>/auth` issuer. It must be literally `localhost` —
`127.0.0.1` gets a 404 from Keycloak's strict admin-hostname check.)

Two related pieces make the lockdown work end to end: `deploy.sh` pins
the **master realm's `frontendUrl`** to the tunnel URL so the console's
cookie/session probes don't hit the public 403, and the war's Keycloak
admin REST traffic bypasses Caddy entirely
(`KEYCLOAK_ADMIN_URL=http://keycloak:8080/auth` in compose.yml). If the
console ever hangs on "loading" with 403s in devtools, rerun
`./deploy.sh` — the frontendUrl pin is idempotent and survives realm
edits, but not a wiped Keycloak database.

**Realm discipline (unchanged from dev):** the realm was imported once,
onto the empty database; from then on the database is authoritative.
Changes made in the console or via admin REST must be mirrored into
`server/keycloak/memberroll-realm.json` in the repo. Never wipe
`data/postgres` of a Keycloak that owns real users — subjects (and
therefore the ownership of everything keyed by `sub`) would be reminted.

Note the production realm is **rendered**, not verbatim: `deploy.sh`
replaces the web client's dev redirect URIs with `https://<domain>/*`,
rotates the `server-service` secret, strips the dev-only fixtures
(`test-cli`, `test-cli-noaud`, the `test*` users), and strips the dev
`smtpServer` block. Enter the real relay once post-import under
**Realm settings → Email** in the tunnel console (same
Exchange mailbox/credentials as the app's Mail settings page) — this is
the ONE deliberate exception to the mirror-back discipline: relay
credentials are environment data like the rotated secret, and the repo
JSON keeps the dev Mailpit block. Until it is entered, forgot-password
mail (the self-serve first-login path, CR-006) does not send.

## 4. Update loop

```bash
# dev machine — after any server/ change
mvn clean package
server/deploy/push-war.sh ubuntu@<instance>
```

`push-war.sh` rsyncs the deploy assets + realm source + war and rebuilds
just the tomcat service; Keycloak/Postgres/Caddy keep running. Config
changes (Caddyfile, compose.yml) ride the same rsync — follow with
`docker compose up -d` on the instance to apply.

### Adding the app database (instances provisioned before CR-001)

Fresh provisions get the `memberroll` database automatically
(`postgres-init/` runs on an empty data dir). An instance provisioned
before CR-001 has Postgres data already, so the init script will never
run — create the database by hand, once:

```bash
cd /opt/memberroll
echo "MEMBERROLL_DB_PASSWORD=$(openssl rand -hex 16)" >> .env
set -a; . ./.env; set +a
docker compose exec postgres psql -U keycloak -d postgres \
  -c "CREATE ROLE memberroll LOGIN PASSWORD '$MEMBERROLL_DB_PASSWORD'" \
  -c "CREATE DATABASE memberroll OWNER memberroll"
docker compose up -d          # tomcat picks up MEMBERROLL_DB_* ; Flyway migrates on start
```

## 5. Backups

Nightly on-box dumps:

```bash
sudo cp systemd/memberroll-backup.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now memberroll-backup.timer
./backup.sh                          # run one now; output lands in backups/
```

Each run dumps **both** databases — `memberroll` (the financial record)
and `keycloak` — keeping 14 nights. Before each period rollover, copy
that night's `memberroll` dump into `backups/archive/` (outside the
pruning) — the year-boundary register is the AGM/audit artefact.

Also schedule **EBS snapshots** in the AWS console (Data Lifecycle
Manager, daily, retain 7) — that covers instance loss until an S3
follow-up.

### Restore

Into scratch containers (never the live ones), e.g.:

```bash
docker run -d --name restore-pg -e POSTGRES_PASSWORD=x postgres:17
gunzip -c backups/memberroll-<ts>.sql.gz | docker exec -i restore-pg psql -U postgres
gunzip -c backups/keycloak-<ts>.sql.gz  | docker exec -i restore-pg psql -U postgres
```

then point a scratch war/Keycloak at them and check a register query
(e.g. `SELECT count(*) FROM person`) and a login. Full-instance restore
= new instance + section 1 + 2 with the restored `data/` in place
before `deploy.sh`.

## 6. Local smoke (dev machine, no AWS)

Runs the production topology locally: Caddy issues a `smoke.localhost`
certificate from its internal CA (no ACME); Tomcat trusts that CA via the
`compose.smoke.yml` entrypoint; everything else is exactly the production
path, including the war's JWKS fetch through the `$DOMAIN` alias.

```bash
rm -rf /tmp/memberroll-smoke && mkdir -p /tmp/memberroll-smoke && cp -r server/deploy/. /tmp/memberroll-smoke/
cp server/keycloak/memberroll-realm.json /tmp/memberroll-smoke/realm-src.json
cp server/target/server.war /tmp/memberroll-smoke/server.war
cd /tmp/memberroll-smoke
export COMPOSE_FILE=compose.yml:compose.smoke.yml KEYCLOAK_ADMIN_PORT=28081
KEEP_TEST_FIXTURES=1 ./deploy.sh smoke.localhost     # keeps test users/clients — smoke ONLY
```

Wait for Keycloak's import, then (with `R=https://smoke.localhost`,
`CURL="curl -sk --resolve smoke.localhost:443:127.0.0.1"`):

- `$CURL $R/server/api/health` → 200
- `$CURL $R/auth/realms/memberroll/.well-known/openid-configuration`
  → issuer `https://smoke.localhost/auth/realms/memberroll`
- `$CURL -o /dev/null -w '%{http_code}' $R/auth/admin/` → 403
- password-grant a token via `test-cli` and call `/server/api/whoami` →
  200 with roles (proves the alias + truststore + single-issuer JWKS path)
- `$CURL -o /dev/null -w '%{redirect_url}' $R/` → `/server/web/`

### The full matrix against the smoke (CR-008)

Production strips the `test-cli` fixtures, so the matrix can never run
against a real instance — the smoke stack is where the production
topology gets the full treatment. The smoke override carries the
matrix's mail/Stripe env and a Mailpit + loopback-Postgres publish;
every environment coupling in `verify-matrix.sh` is env-overridable
(defaults = the dev invocation, byte-identical):

```bash
set -a; . /tmp/memberroll-smoke/.env; set +a
ORIGIN=https://smoke.localhost \
KC_BASE=https://smoke.localhost/auth \
KC_ADMIN_BASE=http://localhost:28081/auth \
CURL_OPTS="-k --resolve smoke.localhost:443:127.0.0.1" \
POSTGRES_PORT=25433 MEMBERROLL_DB_PASSWORD="$MEMBERROLL_DB_PASSWORD" \
MAILPIT_UI_PORT=28025 \
MAILPIT_COMPOSE="docker compose -f /tmp/memberroll-smoke/compose.yml -f /tmp/memberroll-smoke/compose.smoke.yml --project-directory /tmp/memberroll-smoke" \
KEYCLOAK_ADMIN_USER="$KEYCLOAK_ADMIN_USER" KEYCLOAK_ADMIN_PASSWORD="$KEYCLOAK_ADMIN_PASSWORD" \
STRIPE_WEBHOOK_SECRET=whsec_devmatrix \
RELAY_HOST=mailpit RELAY_PORT=1025 \
server/verify-matrix.sh
```

(`KC_ADMIN_BASE` uses the loopback admin port because Caddy 403s the
master realm publicly; `RELAY_HOST=mailpit` is the relay as the
*server* reaches it — Tomcat is inside the compose network here.)

Tear down with `docker compose down` (add `-v` — the smoke's bind mounts
live in the scratch dir and die with it).

Port collisions with the dev stack: the smoke publishes 80/443 (dev uses
neither) and loopback `28081` for Keycloak, `28025` for Mailpit, `25433`
for Postgres (dev owns 18081/18025/5433 — hence the offsets). Dev
Tomcat on 18080 is untouched.

## 7. Testing with real member data — the mail sandbox (CR-021)

When an instance holds **real member addresses** but is being used for
testing (a demo box, a dress rehearsal), turn the app's mail sandbox on
BEFORE touching any mail surface: admin **Mail settings** → set
**Sandbox: redirect all outgoing mail to** a tester's mailbox → Save.
Every message the app sends — including guest-triggered mail from the
public pay/apply pages — then goes to that one address, with the real
recipient named in the subject (`[SANDBOX for jo@example.com]`) and the
body's first line. It applies from the very next message (per-send
resolution, no restart) and every admin page shows an orange SANDBOX
banner while it is on. Clear the field and Save to go live; going live
for real is part of the go-live runbook, and the banner's absence is the
check.

**Paired manual step — Keycloak's own mail is NOT covered.** The
forgot-password / verification email is sent by the realm's `smtpServer`
config, not the app. While testing with provisioned members, point the
realm's SMTP at the same sandbox target via the tunnelled admin console
(realm settings → Email), and restore it afterwards. Forgetting this
means a member testing "Forgot Password" emails a real address.

### Capturing on the box instead (Mailpit as the redirect target)

For full capture with a UI (attachments, HTML view), run a Mailpit on
the instance and use it as the relay while testing:

1. Add a `mailpit` service to the instance's compose file — copy the
   service block from `compose.smoke.yml` (the service **name**
   `mailpit` matters; it is the in-network hostname), publish nothing
   beyond loopback (or nothing at all — the UI is reachable through an
   SSH tunnel to the compose network via `docker compose exec`, or
   publish `127.0.0.1:8025`), then `docker compose up -d`.
2. Mail settings page: Host `mailpit`, Port `1025`, Security `None`,
   **username blank** (no AUTH; the stored Exchange password survives —
   an absent password field keeps it), Save. The sandbox redirect field
   composes with this: pointing the relay at Mailpit captures app mail
   even without the redirect, but the redirect's subject markers still
   tell you who each message was really for.
3. Point the realm's `smtpServer` at `mailpit:1025` too (the paired
   step above).
4. Read captured mail via an SSH tunnel to the Mailpit UI port.
5. To go live again: re-save the real host/port/security/username with
   the password field left empty (keeps the stored secret), clear the
   sandbox field, and restore the realm's `smtpServer`.
