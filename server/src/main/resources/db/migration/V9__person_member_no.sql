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

-- CR-020: the member number the card prints, decoupled from person_id
-- (which is GENERATED ALWAYS and allocated in creation order, forever —
-- it can never honour "life members hold the lowest numbers" or a legacy
-- paper number). Nullable, no backfill: an unassigned person's card keeps
-- showing their person_id (COALESCE at read time), so nothing changes
-- until a number is deliberately assigned. Uniqueness via a partial index
-- so the null majority never collides. "1-30 are for life members" is
-- policy in the manual, deliberately not a constraint here — a range rule
-- would reject legitimate legacy numbers.

ALTER TABLE person ADD COLUMN member_no integer
    CHECK (member_no IS NULL OR member_no > 0);
CREATE UNIQUE INDEX person_member_no ON person (member_no)
    WHERE member_no IS NOT NULL;
