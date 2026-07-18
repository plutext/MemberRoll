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

-- CR-005 segment email. The schema doc reserved "the email send log (CR-005)"
-- as a known later addition. Insert-only in spirit: a send and its recipient
-- rows are a permanent record of what went to whom, and template edits never
-- rewrite a past send (subject/body are SNAPSHOTTED onto email_send). No
-- migration touches communication_preference — CR-001's table is already
-- right; this CR just starts writing to it.

CREATE TABLE email_template (
    email_template_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text NOT NULL UNIQUE,
    subject     text NOT NULL,
    body        text NOT NULL,          -- plain text with {{mergeFields}}
    updated_by  text NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE email_send (
    email_send_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email_template_id    bigint REFERENCES email_template ON DELETE SET NULL,
    subject              text NOT NULL,  -- snapshots: the log shows what was
    body                 text NOT NULL,  -- SENT, template edits can't rewrite it
    membership_period_id bigint NOT NULL REFERENCES membership_period,
    status_filter        text,           -- the segment, as the list-endpoint params
    type_filter          bigint REFERENCES membership_type,
    communication_type   text NOT NULL
                         CHECK (communication_type IN ('NEWSLETTER', 'RENEWAL', 'EVENTS', 'GENERAL')),
    status               text NOT NULL DEFAULT 'RUNNING'
                         CHECK (status IN ('RUNNING', 'COMPLETE', 'ABORTED')),
    created_by           text NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    finished_at          timestamptz
);

CREATE TABLE email_send_recipient (
    email_send_recipient_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email_send_id    bigint NOT NULL REFERENCES email_send ON DELETE RESTRICT,
    membership_id    bigint NOT NULL REFERENCES membership,
    person_id        bigint REFERENCES person,      -- NULL only for NO_EMAIL rows
    email            text,                          -- as resolved at send time
    status           text NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'SENT', 'FAILED',
                                       'SKIPPED_POST', 'SKIPPED_NONE', 'NO_EMAIL')),
    error            text,
    renewal_token_id bigint REFERENCES renewal_token,
    sent_at          timestamptz,
    CHECK ((status = 'NO_EMAIL') = (email IS NULL))
);
CREATE INDEX email_send_recipient_send ON email_send_recipient (email_send_id);

-- first app-level setting (the saved footer); generic on purpose
CREATE TABLE app_setting (
    key        text PRIMARY KEY,
    value      text NOT NULL,
    updated_by text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
