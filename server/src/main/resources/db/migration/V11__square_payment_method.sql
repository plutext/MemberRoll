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

-- CR-029: add SQUARE as a payment method. text+CHECK enum, so this is the
-- one-line migration the schema was designed for — drop and re-add the
-- (V1-inline, Postgres-named) constraint with the new value. SQUARE is a
-- plain hand-entered method (no webhook), so unlike STRIPE it needs no extra
-- guard; the V4 payment_stripe_needs_txn_id constraint is untouched.
ALTER TABLE payment DROP CONSTRAINT payment_payment_method_check;
ALTER TABLE payment ADD CONSTRAINT payment_payment_method_check
    CHECK (payment_method IN ('CASH', 'CHEQUE', 'BANK_TRANSFER', 'STRIPE', 'SQUARE', 'OTHER'));
