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

-- CR-031 attach membership cards to a segment email. A send-level flag plus a
-- NO_CARD bookkeeping status; the subject/body snapshot and the CR-005 flow are
-- otherwise unchanged (attach_card defaults false, so every existing row and
-- every plain segment send behaves exactly as before).

-- The send parameter: when true, startSending attaches every ACTIVE-membership
-- MEMBER card at each recipient's address (CR-017 Cards.compose is the gate).
ALTER TABLE email_send ADD COLUMN attach_card boolean NOT NULL DEFAULT false;

-- A recipient whose membership yields no composable card (e.g. an attach-card
-- send aimed at a non-ACTIVE segment) is recorded NO_CARD and NOT sent — never
-- a "here is your card" email with nothing attached. Unlike NO_EMAIL it keeps
-- its email, so the existing (status = 'NO_EMAIL') = (email IS NULL) check holds.
ALTER TABLE email_send_recipient DROP CONSTRAINT email_send_recipient_status_check;
ALTER TABLE email_send_recipient ADD CONSTRAINT email_send_recipient_status_check
    CHECK (status IN ('PENDING', 'SENT', 'FAILED',
                      'SKIPPED_POST', 'SKIPPED_NONE', 'NO_EMAIL', 'NO_CARD'));
