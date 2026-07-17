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

-- Reference data: the Yass society's membership types and the current
-- period, at the published 2025/26 prices (Single $45, Household $65). The
-- membership year runs 1 September to 31 August.
-- PLACEHOLDERS flagged in CR-001: confirm the current year's prices and
-- the exact renewal-open / late-joining dates with the society before
-- go-live (the period boundary is set; only start/end were confirmed —
-- renewal_open_date and late_joining_cutoff below are proportional
-- placeholders). Another society self-hosting edits these rows (or ships
-- its own V2).

INSERT INTO membership_type (name, description, minimum_people, maximum_people)
VALUES ('SINGLE',    'One person',                          1, 1),
       ('HOUSEHOLD', 'Multiple people at the same address', 2, NULL);

INSERT INTO membership_period (name, start_date, end_date, renewal_open_date, late_joining_cutoff)
VALUES ('2025-2026', DATE '2025-09-01', DATE '2026-08-31', DATE '2025-08-01', DATE '2026-06-01');

INSERT INTO membership_type_price (membership_type_id, membership_period_id, amount_cents)
SELECT t.membership_type_id, p.membership_period_id,
       CASE t.name WHEN 'SINGLE' THEN 4500 WHEN 'HOUSEHOLD' THEN 6500 END
FROM membership_type t, membership_period p
WHERE p.name = '2025-2026';
