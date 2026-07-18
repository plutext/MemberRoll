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

-- CR-013 committee register. A committee position is a term-bounded
-- appointment of a person to an office, AGM to AGM (constitution cl.
-- 14-17) — exactly the current-is-null temporal shape household_person
-- already uses. Keyed on person_id, not membership_person: an appointment
-- spans years and periods, and "is a current statutory member" is a soft
-- application-layer guard (the committee, not the app, is the authority on
-- eligibility), never a DB constraint. Corrections are edits, not
-- reversals — this is administrative reference data, not a financial
-- ledger, so there is deliberately no "negative appointment".

CREATE TABLE committee_appointment (
    committee_appointment_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    person_id     bigint NOT NULL REFERENCES person ON DELETE RESTRICT,
    office        text   NOT NULL
                  CHECK (office IN ('PRESIDENT','VICE_PRESIDENT','SECRETARY','TREASURER','ORDINARY')),
    started_date  date   NOT NULL,   -- the AGM (or casual-vacancy date) the term begins
    ended_date    date,              -- NULL = currently serving; set to the next AGM date
    elected_date  date,              -- when elected/appointed (usually = started_date)
    minute_ref    text,              -- optional pointer into the society's minutes
    notes         text,
    recorded_by   text   NOT NULL,
    recorded_at   timestamptz NOT NULL DEFAULT now(),
    CHECK (ended_date IS NULL OR ended_date >= started_date)
);
-- one person holds a given office at most once concurrently
CREATE UNIQUE INDEX committee_appointment_current
    ON committee_appointment (person_id, office) WHERE ended_date IS NULL;
-- the singular offices have at most one current holder (ordinary seats are many)
CREATE UNIQUE INDEX committee_appointment_singular_office
    ON committee_appointment (office)
    WHERE ended_date IS NULL AND office <> 'ORDINARY';
CREATE INDEX committee_appointment_person ON committee_appointment (person_id);
