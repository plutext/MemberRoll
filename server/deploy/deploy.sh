#!/usr/bin/env bash
# Copyright 2026 Jason Harrop
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# First-run (and re-run — it is idempotent) provisioning driver. Runs ON
# THE INSTANCE in /opt/memberroll after push-war.sh has synced the deploy
# assets, realm-src.json and server.war across.
#
#   ./deploy.sh <domain>     # first run: writes .env with generated secrets
#   ./deploy.sh              # thereafter: re-render realm, rebuild, up
#
# The realm import is rendered from the checked-in realm (realm-src.json,
# a verbatim copy of server/keycloak/memberroll-realm.json): the web client's
# dev redirect URIs are replaced with https://<domain>/*, the
# server-service dev secret is replaced with the generated one, and the
# dev-only fixtures (test-cli, test-cli-noaud, the test users with
# trivial passwords) are STRIPPED — production starts empty. Keycloak
# imports the result once, on an empty database; after that the database
# is authoritative (re-rendering is harmless — import is skipped).
#
# KEEP_TEST_FIXTURES=1 keeps the dev fixtures in the render. That exists
# for the local smoke (README "Local smoke") so the curl matrix can mint
# tokens; never set it on the real instance.
set -euo pipefail
cd "$(dirname "$0")"

for tool in docker python3; do
    command -v "$tool" >/dev/null || { echo "ERROR: $tool not installed" >&2; exit 1; }
done
[ -f server.war ] || { echo "ERROR: server.war missing — run push-war.sh from the dev machine" >&2; exit 1; }
[ -f realm-src.json ] || { echo "ERROR: realm-src.json missing — run push-war.sh from the dev machine" >&2; exit 1; }

if [ ! -f .env ]; then
    [ $# -ge 1 ] || { echo "usage (first run): ./deploy.sh <domain>" >&2; exit 1; }
    umask 077
    cat > .env <<EOF
DOMAIN=$1
KEYCLOAK_ADMIN_USER=admin
KEYCLOAK_ADMIN_PASSWORD=$(openssl rand -hex 16)
KEYCLOAK_SERVICE_SECRET=$(openssl rand -hex 24)
KEYCLOAK_DB_PASSWORD=$(openssl rand -hex 16)
MEMBERROLL_DB_PASSWORD=$(openssl rand -hex 16)
# Mail sender display name — set before go-live (CR-008 runbook).
MEMBERROLL_SOCIETY_NAME=
# Live Stripe (CR-008 runbook step 10): the restricted live API key, and
# the signing secret of the dashboard-registered webhook endpoint
# https://<domain>/server/api/stripe/webhook. Blank = checkout/webhook
# answer 503; everything else works.
STRIPE_SECRET_KEY=
STRIPE_WEBHOOK_SECRET=
EOF
    umask 022
    echo "wrote .env for $1 (secrets generated; keep this file — it IS the credentials)"
fi

set -a; . ./.env; set +a

mkdir -p keycloak-import data/postgres data/caddy backups

DOMAIN="$DOMAIN" KEYCLOAK_SERVICE_SECRET="$KEYCLOAK_SERVICE_SECRET" \
KEEP_TEST_FIXTURES="${KEEP_TEST_FIXTURES:-0}" python3 - <<'EOF'
import json, os

origin = "https://" + os.environ["DOMAIN"]
realm = json.load(open("realm-src.json"))

for client in realm["clients"]:
    if client["clientId"] == "web":
        client["redirectUris"] = [origin + "/*"]
        client["webOrigins"] = [origin]
        client["attributes"]["post.logout.redirect.uris"] = origin + "/*"
    if client["clientId"] == "server-service":
        client["secret"] = os.environ["KEYCLOAK_SERVICE_SECRET"]

if os.environ["KEEP_TEST_FIXTURES"] != "1":
    realm["clients"] = [c for c in realm["clients"]
                        if not c["clientId"].startswith("test-cli")]
    realm["users"] = [u for u in realm["users"]
                      if u["username"] == "service-account-server-service"]
    # The checked-in smtpServer points at the dev/smoke Mailpit, which does
    # not exist in production — import with NO mail config and enter the
    # real relay once via the tunnel console (CR-008 §4: like the rotated
    # secret, relay credentials are environment data the repo JSON never
    # carries; forgot-password mail is dead until this is done).
    realm.pop("smtpServer", None)

json.dump(realm, open("keycloak-import/memberroll-realm.json", "w"), indent=2)
EOF

docker compose build tomcat
docker compose up -d

# Pin the MASTER realm's frontendUrl to the tunnel URL (idempotent).
# The admin console is served at KC_HOSTNAME_ADMIN, but its cookie/session
# probes use the master realm's frontend URL, which otherwise follows
# KC_HOSTNAME — the public hostname, where Caddy 403s the master realm.
# With this pin the console is tunnel-contained end to end.
admin_port=${KEYCLOAK_ADMIN_PORT:-8081}
echo "pinning master realm frontendUrl to http://localhost:$admin_port/auth ..."
for i in $(seq 40); do
    if docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh config credentials \
            --server http://localhost:8080/auth --realm master \
            --user "$KEYCLOAK_ADMIN_USER" --password "$KEYCLOAK_ADMIN_PASSWORD" 2>/dev/null; then
        docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh update realms/master \
            -s "attributes.frontendUrl=http://localhost:$admin_port/auth"
        echo "master frontendUrl pinned"
        break
    fi
    [ "$i" = 40 ] && { echo "ERROR: Keycloak did not come up; rerun ./deploy.sh" >&2; exit 1; }
    sleep 3
done

docker compose ps
echo
echo "https://$DOMAIN/server/api/health is the first thing to curl once DNS points here."
