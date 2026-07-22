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

-- CR-018: instantiate the LIFE membership type. Zero-due memberships are
-- ACTIVE immediately and the rollover carries them at $0 each period (the
-- CR-001 "Life and honorary" convention) — this migration only creates the
-- type; the mechanism has existed since V1. Reference data via migration is
-- the V2 precedent: there is deliberately no type-management API (CR-010).
--
-- Guarded inserts: dev/smoke databases already hold a LIFE type seeded by
-- verify-matrix.sh via psql, and this must converge on them too.

INSERT INTO membership_type (name, description, minimum_people, maximum_people)
SELECT 'LIFE', 'Life member — no annual fee', 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM membership_type WHERE name = 'LIFE');

-- $0 in every existing period, so a retype works in any period and a
-- rollover from any period finds a price. Periods created from here on
-- require a price for every type at creation (PeriodStore), so the
-- invariant holds forward without further migrations.
INSERT INTO membership_type_price (membership_type_id, membership_period_id, amount_cents)
SELECT t.membership_type_id, p.membership_period_id, 0
FROM membership_type t
CROSS JOIN membership_period p
WHERE t.name = 'LIFE'
  AND NOT EXISTS (SELECT 1 FROM membership_type_price tp
                  WHERE tp.membership_type_id = t.membership_type_id
                    AND tp.membership_period_id = p.membership_period_id);
