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

-- CR-004 review hardening: "positive STRIPE only via the webhook" made
-- structural. The webhook always records an external_transaction_id (that
-- is what rule 12's dedup index keys on); hand entry never has one and is
-- only allowed to be negative (recording a dashboard refund). Any future
-- payment entry point that bypasses AdminPaymentsResource's guard hits
-- this instead of silently minting webhook-invisible STRIPE money.
ALTER TABLE payment ADD CONSTRAINT payment_stripe_needs_txn_id
    CHECK (payment_method <> 'STRIPE' OR amount_cents < 0
           OR external_transaction_id IS NOT NULL);
