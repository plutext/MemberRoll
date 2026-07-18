-- Copyright 2026 Jason Harrop
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- CR-006 member self-serve. The schema doc reserved
-- "person.keycloak_subject nullable+unique" as a known later addition.
-- Nullable: most people never get an account. UNIQUE: one person per
-- Keycloak account (Postgres UNIQUE permits many NULLs). The other
-- CR-006 rule — one email address in use in at most one household — is
-- deliberately application-level validation in provisioning, not a
-- constraint: email_address allows shared addresses, and the
-- email→household path runs through household_person history, which a
-- unique index cannot express.

ALTER TABLE person ADD COLUMN keycloak_subject text UNIQUE;
