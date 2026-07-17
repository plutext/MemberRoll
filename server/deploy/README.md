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

mkdir -p /opt/myapp && chown ubuntu:ubuntu /opt/myapp
```

## 2. First deploy

On the **dev machine**:

```bash
mvn clean package
server/deploy/push-war.sh ubuntu@<instance>
```

On the **instance**:

```bash
cd /opt/myapp
./deploy.sh <domain>       # writes .env (generated secrets), renders the
                           # realm, builds the tomcat image, compose up
```

`.env` is the credential set (Keycloak bootstrap admin, DB password,
`server-service` secret) — mode 600, never leaves the box, back it up
somewhere safe once.

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
curl -s https://<domain>/auth/realms/myapp/.well-known/openid-configuration | jq .issuer
#   → "https://<domain>/auth/realms/myapp"   (the single issuer)
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
`server/keycloak/myapp-realm.json` in the repo. Never wipe
`data/postgres` of a Keycloak that owns real users — subjects (and
therefore the ownership of everything keyed by `sub`) would be reminted.

Note the production realm is **rendered**, not verbatim: `deploy.sh`
replaces the web client's dev redirect URIs with `https://<domain>/*`,
rotates the `server-service` secret, and strips the dev-only fixtures
(`test-cli`, `test-cli-noaud`, the `test*` users).

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

## 5. Backups

Nightly on-box dumps:

```bash
sudo cp systemd/myapp-backup.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now myapp-backup.timer
./backup.sh                          # run one now; output lands in backups/
```

Also schedule **EBS snapshots** in the AWS console (Data Lifecycle
Manager, daily, retain 7) — that covers instance loss until an S3
follow-up.

### Restore

Into scratch containers (never the live ones), e.g.:

```bash
docker run -d --name restore-pg -e POSTGRES_PASSWORD=x postgres:17
gunzip -c backups/keycloak-<ts>.sql.gz | docker exec -i restore-pg psql -U postgres
tar xzf backups/store-<ts>.tar.gz -C /tmp/restore-store
```

then point a scratch war/Keycloak at them and check an API query and a
login. Full-instance restore = new instance + section 1 + 2 with the
restored `data/` in place before `deploy.sh`.

## 6. Local smoke (dev machine, no AWS)

Runs the production topology locally: Caddy issues a `smoke.localhost`
certificate from its internal CA (no ACME); Tomcat trusts that CA via the
`compose.smoke.yml` entrypoint; everything else is exactly the production
path, including the war's JWKS fetch through the `$DOMAIN` alias.

```bash
rm -rf /tmp/myapp-smoke && mkdir -p /tmp/myapp-smoke && cp -r server/deploy/. /tmp/myapp-smoke/
cp server/keycloak/myapp-realm.json /tmp/myapp-smoke/realm-src.json
cp server/target/server.war /tmp/myapp-smoke/server.war
cd /tmp/myapp-smoke
export COMPOSE_FILE=compose.yml:compose.smoke.yml KEYCLOAK_ADMIN_PORT=18081
KEEP_TEST_FIXTURES=1 ./deploy.sh smoke.localhost     # keeps test users/clients — smoke ONLY
```

Wait for Keycloak's import, then (with `R=https://smoke.localhost`,
`CURL="curl -sk --resolve smoke.localhost:443:127.0.0.1"`):

- `$CURL $R/server/api/health` → 200
- `$CURL $R/auth/realms/myapp/.well-known/openid-configuration`
  → issuer `https://smoke.localhost/auth/realms/myapp`
- `$CURL -o /dev/null -w '%{http_code}' $R/auth/admin/` → 403
- password-grant a token via `test-cli` and call `/server/api/whoami` →
  200 with roles (proves the alias + truststore + single-issuer JWKS path)
- `$CURL -o /dev/null -w '%{redirect_url}' $R/` → `/server/web/`

Tear down with `docker compose down` (add `-v` — the smoke's bind mounts
live in the scratch dir and die with it).

Port collisions with the dev stack: the smoke publishes 80/443 (dev uses
neither) and loopback `18081` for Keycloak (dev Keycloak owns 8081 —
hence `KEYCLOAK_ADMIN_PORT`). Dev Tomcat on 8080 is untouched.
