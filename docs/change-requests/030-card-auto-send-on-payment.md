# CR-030 — membership card auto-send on online payment

## Problem

When a member pays online through the CR-004 pay link, the Stripe webhook
records the payment and emails them a **receipt** (CR-012). We also want the
system to send them their **membership card** (CR-017) automatically, in a
**separate** email, once the payment has made their membership current — so an
online renewer receives both without any admin action.

Today the card is only ever produced on demand: the member's own
`/api/me/membership/{id}/card/email` and the admin dialog's **Card… → Email**.
Nothing sends it off the back of a payment.

## Objective

After a successful online payment, email each current MEMBER their own
membership card as a separate message — best-effort, once, and only when the
payment actually makes the membership ACTIVE.

## Design

The receipt already rides after the webhook's commit (`sendReceipt`), so this
is a **direct mirror** of that path, not new machinery. The webhook stays the
one online-payment surface.

- **`StripeWebhookResource.sendCards(paymentId, membershipId)`**, called
  immediately after `sendReceipt(...)` in `record()`. Placement is load-bearing
  for idempotency: a Stripe **redelivery** returns at the duplicate-
  `external_transaction_id` (23505) catch *before* this line, so the card — like
  the receipt — is only ever sent on a genuinely-new payment. It is wrapped in
  its own try/catch and uses `Mail.sendAsync`, so a mail failure never fails the
  webhook (the payment is already committed).

- **`Cards.memberPersonIds(handle, membershipId)`** — the one new query. It
  mirrors `Cards.compose`'s live-`household_person` join (MEMBER relationship,
  not-left) **and** its `status = 'ACTIVE'` gate, returning the person ids
  compose will render a card for. Using the live household (not the
  `membership_person` snapshot) is the CR-017 rule, so a person removed from the
  household after creation is correctly not carded.

- Per person: `Cards.compose` → (skip if empty) → `Cards.primaryEmail` → (skip
  if none) → `Mail.sendAsync(to, Cards.subject, Cards.emailBody,
  Cards.attachment)`. Every one of these already exists and is exactly what the
  member/admin card-email endpoints call — the card is byte-for-byte the same
  document across all surfaces (the CR-012/CR-017 one-renderer discipline).

### Recipient model — each MEMBER to their own register email

A card is per-person (own name, own `member_no`), so a household with two
MEMBER-relationship adults gets **two** cards, one per person, each sent to that
person's **own** primary register email (`Cards.primaryEmail`). This is
deliberately **not** the Stripe checkout email the receipt uses: the receipt
goes to whoever paid; the cards go to the members themselves. A member with no
register email is silently skipped (an internal batch — no enumeration oracle
concern, unlike the guest surfaces).

### Behaviours that fall out of the compose gate

- **Partial payment → no card.** If the payment does not cover the balance, the
  recompute leaves the membership non-ACTIVE, so `memberPersonIds`/`compose`
  return nothing and no card is sent. No separate "is it paid?" check is written
  — the gate is reused.
- **PARTNER/DEPENDANT/OTHER never carded** — the join is MEMBER-only, matching
  voting-rights / card entitlement.

### Deliberate asymmetry: online only

Cash/cheque/Square payments are recorded by an admin and do **not** pass through
the webhook, so they do not auto-send a card — they stay covered by the
on-demand admin **Card…** dialog. This is the **same** asymmetry receipts
already have (auto on Stripe, on-demand for manual), so the two transactional
mails behave consistently. If manual payments should ever auto-card, that is a
separate change on `AdminPaymentsResource`, not here.

## Verification

Matrix rows **CR30-\*** (self-cleaning), appended to the CR-004 webhook block
where the signed-event helpers and a Mailpit poll already exist. They require
the webhook rows' preconditions (server `STRIPE_WEBHOOK_SECRET` == the shell's,
and `Mail.enabled()` via the dev SMTP env → Mailpit). A dedicated fixture (Vera,
a household's sole MEMBER, register email `card.v.$$`) keeps the card assertions
clean; CR30-03 reuses the existing Peta fixture, whose register email *equals*
the checkout email, to exercise the shared-address case:

| row | check | expect |
|---|---|---|
| CR30-01 | full-fee signed `checkout.session.completed` for Vera's membership → 200, ACTIVE | 200 / `t` |
| CR30-02 | a **card** email arrived at Vera's register address (`membership card is attached`) with **one PNG attachment** | true / 1 |
| CR30-03 | when the member's register email == the checkout email (Peta), the card **co-lands with** the receipt at that address (both present; the receipt is found by content, not "newest") | true |
| CR30-04 | a **partial** payment (4400 < 4500 due) → 200, membership **not** ACTIVE, **no** card at the member's address | 200 / `f` / 0 |
| CR30-05 | a **redelivery** (same `payment_intent`) → 200 no-op, still exactly **one** card at the member's address | 200 / 1 |

To keep CR4-19 robust to CR30-03's collision, the receipt is now located by
content (`mailpit_text_matching`) instead of "newest message wins" — the one
pre-existing-matrix change this CR makes.

Plus: `mvn -pl server package` compiles; the whole matrix stays green (this CR
adds an after-commit side effect on the existing webhook path and changes no
status codes or bodies, so all prior rows are unaffected).

## What this CR does NOT do

- No new endpoint, no schema change, no new mail template — the card email is
  the existing CR-017 subject/body/attachment.
- No card on manual (non-webhook) payments (see the asymmetry above).
- No card on a partial payment (the ACTIVE gate).
- Does not change the receipt, its recipient, or its timing.

## Results (2026-08-11, Opus 4.8)

Implemented: `Cards.memberPersonIds` + `StripeWebhookResource.sendCards` (called
after `sendReceipt`). `mvn -pl server package` compiles; cargo restarted with the
dev SMTP + `STRIPE_WEBHOOK_SECRET=whsec_devmatrix` env.

End-to-end (dev stack, self-signed webhook → Mailpit; gitignored
`tmp/cr030-fixtures/`): **14/14** core checks — full payment → ACTIVE → card PNG
to the member's register email; receipt still to the *checkout* email, separately,
no attachment (two distinct mails); partial → not ACTIVE → no card; redelivery →
no duplicate. Plus **7/7** two-MEMBER checks — a HOUSEHOLD membership cards **each**
MEMBER at their **own** address, each card naming its own person.

Full matrix (`verify-matrix.sh`, committed CR30 rows): **992 / 1**. All ten
**CR30-\*** rows pass (full 200/ACTIVE, card at register email with one PNG
attachment, shared-address co-land, partial→no-card, redelivery→no-dup), and
the formerly-failing **CR4-19** receipt row now passes.

The **one** failure is **CR4-01c**, pre-existing and unrelated: it asserts a
pay-link token "expires at period end", but the mint enforces a ≥30-day expiry
floor and the run date (2026-08-11) is within 30 days of the 2025-2026 period
end (2026-08-31), so the token expires past period-end. A calendar-driven flake
in CR-004 code this CR does not touch (same one recorded in the CR-029 results).

Diagnosis folded in: CR4-19 failed on the first run because the matrix fixture
gives Peta (a MEMBER — `HouseholdStore` inserts the primary contact as `MEMBER`)
the register email `receipt.$$@example.com`, i.e. the same address used as the
Stripe checkout email; the new card correctly lands there too, *after* the
receipt, so `mailpit_text`'s "newest wins" picked the card. Product behaviour is
correct (a member paying for themselves gets both mails in one inbox); the fix
was to locate the receipt **by content** (`mailpit_text_matching`). NOT yet
committed/deployed.
