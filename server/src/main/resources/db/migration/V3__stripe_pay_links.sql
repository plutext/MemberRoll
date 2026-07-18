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

-- CR-004: magic-link pay tokens and the per-period journal add-on price —
-- exactly the "known later additions" the schema doc reserved for Stripe.

-- Only the sha256 of a token is stored, so a database leak leaks no usable
-- pay links. A token is NOT single-use (an abandoned Checkout or re-clicked
-- email link must not dead-end a member): expires_at is the gate, used_at is
-- bookkeeping. A membership may hold several unexpired tokens — hash-only
-- storage means a mint can never re-present an earlier token, so each mint
-- issues a fresh one and the older links simply stay valid until expiry.
CREATE TABLE renewal_token (
    renewal_token_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    membership_id    bigint NOT NULL REFERENCES membership ON DELETE RESTRICT,
    token_hash       text NOT NULL UNIQUE,   -- sha256 hex; the raw token is never stored
    created_at       timestamptz NOT NULL DEFAULT now(),
    expires_at       timestamptz NOT NULL,
    used_at          timestamptz             -- first successful payment through this token
);
CREATE INDEX renewal_token_membership ON renewal_token (membership_id);

-- journal add-on price per period; NULL = the add-on is not offered
ALTER TABLE membership_period ADD COLUMN journal_price_cents integer
    CHECK (journal_price_cents IS NULL OR journal_price_cents >= 0);
