#!/usr/bin/env bash
# Instantiate this template as a new project, in place:
#
#   ./init.sh <project-id> <java-package> [display-name]
#   ./init.sh myproject com.acme.myproject "My Project"
#
# - project-id: lowercase letters/digits, starts with a letter (max 32).
#   Becomes the Maven artifactId prefix, Keycloak realm name, token
#   audience (<id>-server), env prefix (<ID>_DATA), /opt/<id>, systemd
#   unit names.
# - java-package: replaces com.example.myapp (sources move accordingly);
#   its parent becomes the Maven groupId.
# - display-name: optional; becomes the Keycloak realm displayName
#   (what the hosted login page shows). Defaults to the project id.
#
# The script rewrites every file, renames the realm/systemd files, moves
# the Java package directories, re-initializes git with one fresh
# initial commit, and deletes itself. Run it exactly once, from a fresh
# clone.
set -euo pipefail
cd "$(dirname "$0")"

ID=${1:?usage: ./init.sh <project-id> <java-package> [display-name]}
PKG=${2:?usage: ./init.sh <project-id> <java-package> [display-name]}
DISPLAY=${3:-$ID}

[[ "$ID" =~ ^[a-z][a-z0-9]{0,31}$ ]] || { echo "ERROR: project-id must be lowercase letters/digits, starting with a letter" >&2; exit 1; }
[[ "$PKG" =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]] || { echo "ERROR: java-package must look like com.acme.myproject" >&2; exit 1; }
[[ "$DISPLAY" =~ [\|] ]] && { echo "ERROR: display-name must not contain |" >&2; exit 1; }
[ -d server/src/main/java/com/example/myapp ] || { echo "ERROR: already initialized (template package gone)" >&2; exit 1; }

UPPER=$(printf '%s' "$ID" | tr '[:lower:]' '[:upper:]')
GROUP=${PKG%.*}

# ---- rewrite file contents -------------------------------------------------
# Order matters: the full package first, then the bare groupId, then the
# uppercase env prefix, then the id itself (which is a substring of all
# of the above).
files=$(grep -rl --exclude-dir=.git --exclude-dir=target --exclude=init.sh \
        -e 'com\.example\.myapp' -e 'com\.example' -e 'MYAPP' -e 'myapp' . || true)
for f in $files; do
    sed -i \
        -e "s|com\.example\.myapp|$PKG|g" \
        -e "s|com\.example|$GROUP|g" \
        -e "s|MYAPP|$UPPER|g" \
        -e "s|myapp|$ID|g" \
        "$f"
done

# ---- rename files and move the Java package ---------------------------------
mv server/keycloak/myapp-realm.json "server/keycloak/${ID}-realm.json"
mv server/deploy/systemd/myapp-backup.service "server/deploy/systemd/${ID}-backup.service"
mv server/deploy/systemd/myapp-backup.timer "server/deploy/systemd/${ID}-backup.timer"

src=server/src/main/java/com/example/myapp/server
dst=server/src/main/java/$(printf '%s' "$PKG" | tr . /)/server
mkdir -p "$(dirname "$dst")"
mv "$src" "$dst"
# prune the now-empty template package dirs
rmdir -p server/src/main/java/com/example 2>/dev/null || true

# the realm's human-facing name (hosted login/registration pages)
sed -i "s|\"displayName\": \"$ID\"|\"displayName\": \"$DISPLAY\"|" "server/keycloak/${ID}-realm.json"

# ---- fresh README + git history ---------------------------------------------
cat > README.md <<EOF
# $DISPLAY

A Tomcat/Jersey/Keycloak webapp (from webapp-template).

- Getting started (dev loop, LAN phones, production): [docs/GETTING-STARTED.md](docs/GETTING-STARTED.md)
- Server details and Keycloak primer: [server/README.md](server/README.md)
- Deployment: [server/deploy/README.md](server/deploy/README.md)
- Workflow: [docs/change-requests/](docs/change-requests/)

Dev loop:

\`\`\`bash
mvn clean package
(cd server && docker compose up -d)   # Keycloak :8081
mvn -pl server cargo:run              # Tomcat :8080 → http://localhost:8080/server/web/
\`\`\`
EOF

rm -rf .git
rm -- "$0"
git init -q
git add -A
git commit -qm "Initial commit: $ID from webapp-template"

echo "Initialized '$ID' (package $PKG, realm '$ID', display name '$DISPLAY')."
echo "Next: docs/GETTING-STARTED.md — and add your LICENSE before pushing."
