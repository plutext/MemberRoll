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
