#!/usr/bin/env bash
# Nightly on-box backup (systemd timer — see systemd/). Dumps the
# Keycloak database and tars the data store into ./backups, keeping 14
# nights. On-box only: this protects against application-level mistakes;
# instance loss is covered by console-scheduled EBS snapshots (or add an
# S3 sync). Restore drill: deploy README, "Restore".
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a

ts=$(date +%Y%m%d_%H%M%S)
mkdir -p backups

docker compose exec -T postgres pg_dump -U keycloak keycloak | gzip > "backups/keycloak-$ts.sql.gz"
tar czf "backups/store-$ts.tar.gz" -C data store

find backups -name '*.gz' -mtime +14 -delete
echo "backup $ts done: $(du -sh backups | cut -f1) total"
