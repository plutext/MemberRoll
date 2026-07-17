#!/usr/bin/env bash
# The update loop, run ON THE DEV MACHINE from anywhere in the repo.
# Build stays local (the instance never compiles anything):
#
#   mvn clean package
#   server/deploy/push-war.sh <user@host>
#
# Syncs the deploy assets + realm source + freshly built war to
# /opt/myapp and rebuilds/restarts the tomcat service. On a
# never-provisioned instance it only syncs and tells you to run deploy.sh.
#
# Extra ssh options (identity file, port, ...) go in SSH_OPTS:
#   SSH_OPTS="-i $HOME/.ssh/myapp.pem" push-war.sh ubuntu@<host>
# ($HOME, not ~ — tilde does not expand when rsync runs the command.)
set -euo pipefail
HOST=${1:?usage: push-war.sh <user@host> [remote-dir]}
DIR=${2:-/opt/myapp}
cd "$(dirname "$0")"

[ -f ../target/server.war ] || { echo "ERROR: server/target/server.war missing — mvn clean package first" >&2; exit 1; }

rsync -av -e "ssh ${SSH_OPTS:-}" --exclude data/ --exclude backups/ --exclude .env --exclude keycloak-import/ \
    ./ "$HOST:$DIR/"
rsync -av -e "ssh ${SSH_OPTS:-}" ../keycloak/myapp-realm.json "$HOST:$DIR/realm-src.json"
rsync -av -e "ssh ${SSH_OPTS:-}" ../target/server.war "$HOST:$DIR/server.war"

# word splitting of SSH_OPTS is intentional
ssh ${SSH_OPTS:-} "$HOST" "cd '$DIR' && if [ -f .env ]; then docker compose build tomcat && docker compose up -d; else echo 'synced — now run: cd $DIR && ./deploy.sh <domain>'; fi"
