#!/bin/sh
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

# Creates the app's register database beside Keycloak's. Runs under the
# docker-entrypoint-initdb.d contract: on an EMPTY data dir only (fresh
# provision). For an already-provisioned instance, run the equivalent by
# hand — see deploy/README.md "Adding the app database".
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<EOSQL
CREATE ROLE memberroll LOGIN PASSWORD '$MEMBERROLL_DB_PASSWORD';
CREATE DATABASE memberroll OWNER memberroll;
EOSQL
