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

-- The membership register: docs/membership_management_database_schema.md
-- (as amended 2026-07-17) rendered as DDL. The household is the billing
-- unit, the person is the member unit; history is preserved by inserting
-- new rows, never overwriting — hence ON DELETE RESTRICT everywhere and
-- no soft-delete columns. Money is integer cents; enums are text+CHECK
-- (adding a value is a one-line migration, and it keeps the SQL portable).

CREATE TABLE person (
    person_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title           text,
    given_name      text NOT NULL,
    family_name     text NOT NULL,
    preferred_name  text,
    date_of_birth   date,
    deceased_date   date,
    notes           text
);

CREATE TABLE household (
    household_id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_name            text,
    primary_contact_person_id bigint NOT NULL REFERENCES person ON DELETE RESTRICT,
    status                    text NOT NULL DEFAULT 'ACTIVE'
                              CHECK (status IN ('ACTIVE', 'CLOSED'))
);

CREATE TABLE household_person (
    household_person_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id          bigint NOT NULL REFERENCES household ON DELETE RESTRICT,
    person_id             bigint NOT NULL REFERENCES person ON DELETE RESTRICT,
    relationship_type     text NOT NULL
                          CHECK (relationship_type IN ('MEMBER', 'PARTNER', 'DEPENDANT', 'OTHER')),
    joined_household_date date NOT NULL,
    left_household_date   date,
    CHECK (left_household_date IS NULL OR left_household_date >= joined_household_date)
);
-- a person may leave and rejoin (two rows), but has at most one CURRENT row per household
CREATE UNIQUE INDEX household_person_current
    ON household_person (household_id, person_id) WHERE left_household_date IS NULL;
CREATE INDEX household_person_person ON household_person (person_id);

CREATE TABLE membership_type (
    membership_type_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name               text NOT NULL UNIQUE,
    description        text,
    minimum_people     integer,
    maximum_people     integer,
    active_from        date,
    active_to          date
);

CREATE TABLE membership_period (
    membership_period_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                 text NOT NULL UNIQUE,
    start_date           date NOT NULL,
    end_date             date NOT NULL,
    renewal_open_date    date,
    late_joining_cutoff  date,
    CHECK (end_date > start_date)
);

-- prices per type per period: fee changes never overwrite history (rule 10)
CREATE TABLE membership_type_price (
    membership_type_id   bigint NOT NULL REFERENCES membership_type ON DELETE RESTRICT,
    membership_period_id bigint NOT NULL REFERENCES membership_period ON DELETE RESTRICT,
    amount_cents         integer NOT NULL CHECK (amount_cents >= 0),
    PRIMARY KEY (membership_type_id, membership_period_id)
);

CREATE TABLE membership (
    membership_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    membership_period_id bigint NOT NULL REFERENCES membership_period ON DELETE RESTRICT,
    membership_type_id   bigint NOT NULL REFERENCES membership_type ON DELETE RESTRICT,
    household_id         bigint NOT NULL REFERENCES household ON DELETE RESTRICT,
    -- state only; WHY it ended is cessation_reason (a death never ends a
    -- household membership — that is person.deceased_date + an end date
    -- on the membership_person row)
    status               text NOT NULL
                         CHECK (status IN ('APPLIED', 'PENDING_PAYMENT', 'ACTIVE', 'LAPSED', 'CEASED')),
    application_date     date,
    approved_date        date,
    start_date           date NOT NULL,
    end_date             date NOT NULL,
    -- billed snapshot at rollover/application time; amounts PAID are
    -- derived from payment_allocation, never stored here (rule 6)
    amount_due_cents     integer NOT NULL CHECK (amount_due_cents >= 0),
    ceased_date          date,
    cessation_reason     text CHECK (cessation_reason IN ('RESIGNED', 'DECEASED', 'OTHER')),
    UNIQUE (household_id, membership_period_id),
    CHECK (end_date > start_date),
    CHECK ((status = 'CEASED') = (ceased_date IS NOT NULL))
);
CREATE INDEX membership_period_idx ON membership (membership_period_id);

CREATE TABLE membership_person (
    membership_person_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    membership_id          bigint NOT NULL REFERENCES membership ON DELETE RESTRICT,
    person_id              bigint NOT NULL REFERENCES person ON DELETE RESTRICT,
    membership_role        text,
    is_statutory_member    boolean NOT NULL DEFAULT true,
    has_voting_rights      boolean NOT NULL DEFAULT true,
    eligible_for_committee boolean NOT NULL DEFAULT true,
    start_date             date,
    end_date               date,
    UNIQUE (membership_id, person_id)
);
CREATE INDEX membership_person_person ON membership_person (person_id);

CREATE TABLE payment (
    payment_id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    received_date           date NOT NULL,
    amount_cents            integer NOT NULL CHECK (amount_cents <> 0),
    payment_method          text NOT NULL
                            CHECK (payment_method IN ('CASH', 'CHEQUE', 'BANK_TRANSFER', 'STRIPE', 'OTHER')),
    payer_person_id         bigint REFERENCES person ON DELETE RESTRICT,
    bank_reference          text,
    external_transaction_id text,
    reconciliation_status   text NOT NULL DEFAULT 'UNRECONCILED'
                            CHECK (reconciliation_status IN ('UNRECONCILED', 'RECONCILED')),
    -- audit trail: committees change treasurers
    recorded_by             text NOT NULL,
    recorded_at             timestamptz NOT NULL DEFAULT now(),
    notes                   text
);
-- what makes a redelivered payment webhook a no-op instead of a
-- double-recorded payment (rule 12)
CREATE UNIQUE INDEX payment_external_txn
    ON payment (external_transaction_id) WHERE external_transaction_id IS NOT NULL;

CREATE TABLE payment_allocation (
    payment_allocation_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id            bigint NOT NULL REFERENCES payment ON DELETE RESTRICT,
    allocation_type       text NOT NULL
                          CHECK (allocation_type IN ('MEMBERSHIP', 'JOURNAL', 'DONATION', 'OTHER')),
    membership_id         bigint REFERENCES membership ON DELETE RESTRICT,
    amount_cents          integer NOT NULL CHECK (amount_cents <> 0),
    CHECK (allocation_type <> 'MEMBERSHIP' OR membership_id IS NOT NULL)
);
CREATE INDEX payment_allocation_payment ON payment_allocation (payment_id);
CREATE INDEX payment_allocation_membership ON payment_allocation (membership_id);

CREATE TABLE household_address (
    household_address_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id         bigint NOT NULL REFERENCES household ON DELETE RESTRICT,
    address_type         text NOT NULL CHECK (address_type IN ('POSTAL', 'RESIDENTIAL')),
    line_1               text NOT NULL,
    line_2               text,
    locality             text,
    state                text,
    postcode             text,
    country              text,
    valid_from           date,
    valid_to             date,
    is_preferred         boolean NOT NULL DEFAULT false
);
CREATE INDEX household_address_household ON household_address (household_id);

CREATE TABLE email_address (
    email_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    person_id  bigint NOT NULL REFERENCES person ON DELETE RESTRICT,
    -- lowercase-on-write, enforced: matching is case-insensitive, and NOT
    -- unique across people — couples share an address in this demographic
    email      text NOT NULL CHECK (email = lower(email)),
    is_primary boolean NOT NULL DEFAULT false,
    valid_from date,
    valid_to   date
);
CREATE INDEX email_address_person ON email_address (person_id);
CREATE INDEX email_address_email ON email_address (email);

CREATE TABLE phone_number (
    phone_number_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    person_id       bigint NOT NULL REFERENCES person ON DELETE RESTRICT,
    number          text NOT NULL,
    phone_type      text CHECK (phone_type IN ('MOBILE', 'HOME', 'WORK')),
    is_primary      boolean NOT NULL DEFAULT false,
    valid_from      date,
    valid_to        date
);
CREATE INDEX phone_number_person ON phone_number (person_id);

CREATE TABLE communication_preference (
    communication_preference_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    person_id                   bigint REFERENCES person ON DELETE RESTRICT,
    household_id                bigint REFERENCES household ON DELETE RESTRICT,
    communication_type          text NOT NULL
                                CHECK (communication_type IN ('NEWSLETTER', 'RENEWAL', 'EVENTS', 'GENERAL')),
    delivery_method             text NOT NULL
                                CHECK (delivery_method IN ('EMAIL', 'POST', 'SMS', 'NONE')),
    consent_status              text,
    effective_from              date,
    effective_to                date,
    -- a preference belongs to a person XOR a household
    CHECK ((person_id IS NULL) <> (household_id IS NULL))
);
