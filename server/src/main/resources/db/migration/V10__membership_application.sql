-- CR-007: public membership applications — a STAGING record, deliberately
-- outside the register. The register (clause 4) is never-delete and means
-- "the committee let this in"; a web submission is neither, so junk must be
-- deletable and approval is the only door into person/household/membership.
-- The membership.status value 'APPLIED' (V1) stays reserved and unused: this
-- table IS the applied state.

CREATE TABLE membership_application (
    application_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status              text NOT NULL DEFAULT 'RECEIVED' CHECK (status IN
                          ('RECEIVED', 'CONFIRMED', 'APPROVED', 'REJECTED')),
    submitted_at        timestamptz NOT NULL DEFAULT now(),
    submitted_ip        text,
    -- CR-004 token discipline: sha256 hex only, the raw value exists only in
    -- the confirmation email; expiry is the gate
    confirm_token_hash  text NOT NULL,
    confirm_expires_at  timestamptz NOT NULL,
    confirmed_at        timestamptz,
    -- the type the applicant asked for — advisory; the admin chooses at approval
    membership_type_id  bigint NOT NULL REFERENCES membership_type,
    -- optional postal address, mirroring household_address's columns so
    -- approval can materialise it as the household's POSTAL row
    address_line_1      text,
    address_line_2      text,
    locality            text,
    state               text,
    postcode            text,
    applicant_message   text,
    -- the committee's decision (clause 3), recorded not made here: the date is
    -- the meeting's, decided_by is the admin who recorded it
    decision_date       date,
    minute_reference    text,
    rejection_reason    text,   -- internal only, never emailed
    decided_by          text,
    created_household_id  bigint REFERENCES household,
    created_membership_id bigint REFERENCES membership,
    CHECK ((status IN ('APPROVED', 'REJECTED')) = (decision_date IS NOT NULL))
);
CREATE INDEX membership_application_token ON membership_application (confirm_token_hash);

-- One row per named person. A relationship_type MEMBER row IS a clause-3
-- applicant (approval must name each); PARTNER/DEPENDANT/OTHER rows are
-- covered people, not applicants. ON DELETE CASCADE makes junk removal one
-- statement — the staging pair is the app's only deletable people data.
CREATE TABLE membership_application_person (
    application_person_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id  bigint NOT NULL REFERENCES membership_application ON DELETE CASCADE,
    position        smallint NOT NULL,  -- 1 = the submitting applicant (owns the confirmed email)
    given_name      text NOT NULL,
    family_name     text NOT NULL,
    email           text,               -- required for position 1 (the round-trip address)
    phone           text,
    relationship    text NOT NULL DEFAULT 'MEMBER' CHECK (relationship IN
                      ('MEMBER', 'PARTNER', 'DEPENDANT', 'OTHER')),
    UNIQUE (application_id, position)
);
