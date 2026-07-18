# CR 012: Payment receipts — email or print, for manual payments too

Status: PROPOSED (2026-07-18)

## Problem

CR-004 sends a receipt email only on the Stripe path (the webhook
composes it and mails the address the payer typed into Checkout). A
CR-003 manual payment — cash, cheque, bank transfer, today's main
channel — records perfectly but can produce no receipt at all: nothing
to email, nothing to hand over at the counter.

Constitution clause 38(3)(b) obliges the society to issue a receipt for
money received **if requested** — so the right shape is an on-demand
admin action per payment, not an automatic send on entry (many
bank-transfer payers neither want nor need one, and "if requested" is
the treasurer's call).

## Approach

### One receipt renderer

Extract the receipt composition into a shared helper (working name
`Receipts`), rendered from the RECORDED payment — not from request-time
inputs — so every path shows the same document:

- header: society name, "Receipt #<paymentId>", received date, payment
  method (+ bank reference when present), recorded-by;
- one line per allocation: `Membership <period> (<type>)` /
  `Journal add-on` / `Donation` / `Other`, with amount;
- total (the payment's amount_cents);
- the "your membership is now active — you are financial for <period>"
  line for each MEMBERSHIP-allocated membership that is ACTIVE;
- footer: society name.

The payment id is the receipt number: payments are insert-only and
corrections are negative payments (rule: Reverse, never edit), so the
numbering is stable and audit-friendly. `StripeWebhookResource`'s
inline builder is refactored onto this renderer (it already records the
payment before sending, so it can render from the recorded row; only
its recipient — the Checkout `customer_details` email — stays
Stripe-specific).

A negative payment renders too, headed "Refund record" — the treasurer
may want paper evidence of a refund. **What this is not**: a
DGR-deductible donation receipt (roadmap out-of-scope) — the wording
stays a plain payment receipt so it cannot be mistaken for one.

### Endpoints (both `@RolesAllowed("admin")`, on AdminPaymentsResource)

| method | path | behaviour |
|---|---|---|
| GET | `/api/admin/payments/{id}/receipt` | the composed receipt as JSON: the header/line/total fields plus `text` (the canonical plain-text rendering) and `defaultTo` (see below, null when none). 404 unknown payment. |
| POST | `/api/admin/payments/{id}/receipt` | email it. Body `{to?}`; when `to` is absent, the default address is used, or 400 when there is none. 503 when mail is not configured (`Mail.enabled()` false) — an explicit refusal, mirroring checkout's, never a silent no-op. 404 unknown payment. Stateless: re-sending re-composes; nothing is written. |

Default address resolution: the payment's `payer_person_id`'s primary
email when set; else the CR-005/CR-006 attributed address of the
household behind the payment's first MEMBERSHIP allocation (primary
contact wins); else null (admin must supply `to`).

### UI (admin, membership detail dialog)

Beside each payment row's **Reverse** button: **Receipt…** opens a
small dialog showing the rendered receipt (from the GET), with:

- **Print** — opens a new window containing just the receipt (plain
  semantic HTML, monospace amounts, no app chrome) and calls
  `window.print()`; framework-free per house style, no print CSS
  gymnastics against the admin page itself;
- **Email** — a `to` field prefilled with `defaultTo`, Send posts it;
  the existing mail-disabled banner logic applies (button disabled with
  a hint when the server reports 503).

### Rejected alternatives

- **Auto-email on manual payment entry** — clause 38(3)(b) is
  on-request; unsolicited receipts to bank-transfer payers are noise,
  and entry happens in bulk from statements.
- **A receipt log (issued-at column/table)** — stateless is simpler and
  re-issue is harmless; if "was a receipt issued" ever needs to be
  auditable, a nullable `receipt_sent_at` is a small follow-up, noted
  below.
- **PDF generation server-side** — a dependency and a template engine
  for something the browser's print dialog already does well at this
  scale.
- **Reusing CR-005 segment-email machinery** — that pipeline is
  segment-shaped (send log, merge fields, sequential sender); a receipt
  is one transactional mail, which is exactly what `Mail.send` is for.

### Config

None new. Uses the existing SMTP env; no schema change; no realm
change.

## Verification plan

Extend verify-matrix.sh (CR12-* rows) against a fixture payment P
(BANK_TRANSFER, MEMBERSHIP 4500 + DONATION 500 allocations, payer with
a primary email) and a fixture payment N (negative reversal):

| # | caller | call | expect |
|---|---|---|---|
| 1 | guest / testuser / noaud | GET /api/admin/payments/{P}/receipt | 403 / 403 / 401 |
| 2 | guest / testuser | POST .../receipt | 403 / 403 |
| 3 | testadmin | GET .../receipt | 200; `text` contains "Receipt #<P>", the method, both allocation lines, total $50.00; `defaultTo` = payer's email |
| 4 | testadmin | GET for a membership-only payment whose payer is unset | `defaultTo` = household's attributed address |
| 5 | testadmin | GET /api/admin/payments/999999/receipt | 404 |
| 6 | testadmin | POST .../receipt (no body) | 202/200; Mailpit: mail to `defaultTo`, body equals the GET's `text` |
| 7 | testadmin | POST with {"to": override} | mail lands at the override, not the default |
| 8 | testadmin | POST for a payment with no resolvable default and no `to` | 400 |
| 9 | testadmin | GET /api/admin/payments/{N}/receipt | 200; headed as refund record, negative amounts |
| 10 | — | server without SMTP_HOST (conditional row, CR4-09u pattern) | POST answers 503 |
| 11 | testadmin | membership ACTIVE via the payment | receipt carries the "financial for <period>" line |

Regression: the CR-004 webhook receipt rows (CR4-19) still pass after
the renderer refactor — same content assertions, now through the shared
renderer.

Browser walkthrough: open a membership → payment row → Receipt… →
dialog shows the receipt; Print opens the print window with only the
receipt content; Email with Mailpit round trip; mail-disabled hint when
cargo runs without SMTP env.

## Results

(to be recorded when implemented)

## Follow-ups / amendments

- Possible later: nullable `receipt_sent_at`/log if "was a receipt
  issued" needs to be auditable (deliberately not in v1 — stateless).
