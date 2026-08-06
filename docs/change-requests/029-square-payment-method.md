# CR-029 — SQUARE payment method

## Problem

The society takes some member payments through **Square**. Today the only
hand-enterable methods are `CASH`, `CHEQUE`, `BANK_TRANSFER`, `OTHER`
(`STRIPE` exists but is webhook-only for positives). A treasurer recording a
Square payment therefore has to file it under `OTHER` or `BANK_TRANSFER`,
which blurs it into unrelated money — and Square payments are precisely the
ones that need to be told apart at reconciliation, because Square deposits the
amount **less its processing fee**, so the bank line never equals the sum of
the memberships it paid. Without a distinct method there is no clean way to
subtotal Square receipts and match them to a Square payout.

## Objective

Add `SQUARE` as a first-class payment method so Square receipts can be
recorded and subtotalled distinctly, without changing anything about how
paid-ness or fees work.

## Design

`payment_method` is a `text` + `CHECK` enum (CLAUDE.md: "adding a value is a
one-line migration"). The change is small and crosses schema → server → UI, so
it follows the migration + matrix discipline.

- **V11** drops and re-adds `payment_payment_method_check` with `SQUARE` in the
  list. The V4 `payment_stripe_needs_txn_id` constraint is untouched (it only
  gates `STRIPE`).
- **`AdminPaymentsResource.METHODS`** gains `"SQUARE"`. `SQUARE` is a **plain
  hand-entered method** — positive amounts are allowed, exactly like
  `CASH`/`BANK_TRANSFER`. It deliberately does **not** get `STRIPE`'s
  "positive-only-via-webhook" gate: Square has no webhook integration here, so
  positives are entered by hand. (If a Square integration is ever added, revisit
  this the way `STRIPE` is gated.)
- **`ReconciliationStore.METHOD_ORDER`** gains `"SQUARE"` so it sorts into the
  per-method summary breakout in a stable place (a non-canonical method already
  appears defensively via `putIfAbsent`; this just orders it).
- **UI**: `SQUARE` added to the record-payment method dropdown
  (`index.html#payMethod`) and the reconciliation Method filter
  (`reports.html#recMethod`).

### Fees are deliberately out of scope

Record the **gross** membership fee, never the net banked amount — the
`MEMBERSHIP` allocation must equal the fee owed or the membership shows
underpaid (recompute, rule 6). The Square fee is a reconciliation concern,
handled in Xero via a Square clearing account exactly as Stripe fees are
(gross in, fee + net payout out, nets to zero). Capturing the fee **inside**
the app (a per-payment fee/payout field feeding the generated Xero journal) is
the *same* deferred follow-up already recorded for Stripe in CR-015 ("Stripe
fee/payout-id capture"); if it is ever built it should be built once for both
card processors, not bolted onto SQUARE alone. Consequently the CR-015
xero-journal (which force-selects `STRIPE`) is **not** extended to `SQUARE` in
this CR — Square fees stay a manual/bank-rule reconciliation for now.

## Verification

Matrix rows **CR29-\*** (self-cleaning, appended to the CR-003 payment block
where a membership `$MA` is in scope):

| row | check | expect |
|---|---|---|
| CR29-01 | record a positive `SQUARE` MEMBERSHIP payment on `$MA` | 201 |
| CR29-01b | it persisted with `payment_method = 'SQUARE'` | SQUARE |
| CR29-02 | reverse it (negative `SQUARE`) — restores `$MA` net, self-cleaning | 201 |
| CR29-03 | an invalid method (`SQUAREX`) is still rejected | 400 |
| CR29-04 | a **positive** `STRIPE` is still refused (STRIPE gate intact) | 400 |

Plus: `mvn -pl server package` compiles; a browser check that the record-payment
dropdown offers **Square** and a `SQUARE` payment flips Unpaid → Paid.

## What this CR does NOT do

- No Square API/webhook, no automatic fee/payout capture, no Xero-journal
  change (all the same deferred follow-up as Stripe fees).
- No backfill/relabelling of existing `OTHER`/`BANK_TRANSFER` rows previously
  used for Square — relabel by hand if wanted (payments are insert-only; a
  reclassification is a reverse + re-record, not an edit).

## Results (2026-08-06, Opus 4.8)

Implemented: V11 migration, `AdminPaymentsResource.METHODS`,
`ReconciliationStore.METHOD_ORDER`, the two dropdowns. `mvn -pl server package`
compiles; on cargo restart Flyway applied V11 (`flyway_schema_history` v11 =
success) and the CHECK now lists `SQUARE`.

Matrix: **982 / 1**. All six **CR29-\*** rows pass (positive SQUARE 201, method
persisted, reversal 201, net restored, invalid method 400, positive STRIPE still
400 — the STRIPE gate is intact). The single failure is **CR4-01c**, unrelated:
it asserts a pay-link token "expires at period end", but the mint enforces a
≥30-day expiry floor, and the run date (2026-08-06) is within 30 days of the
2025-2026 period end (2026-08-31), so the token expires 2026-09-05 — past
period-end. A calendar-driven pre-existing flake (a far-future period's tokens
still pass), in CR-004 code this CR does not touch. NOT yet committed/deployed.
