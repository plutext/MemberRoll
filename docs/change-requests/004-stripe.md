# CR 004: Stripe — magic-link pay page, Checkout, webhook, receipt email

Status: VERIFIED (2026-07-18) — full matrix 266/266 including the
Stripe-sandbox checkout rows, plus Jason's six-step browser walkthrough
against Stripe sandbox + `stripe listen` (see Results).

Implementation order note: CR-009 (UI polish) lands first, so the
public pay page is built on the Pico baseline from day one rather than
retrofitted (this discharges CR-009's follow-up for new pages).

## Problem

After CR-003 the treasurer can run a membership year, but every dollar
still arrives out-of-band (bank transfer with a name in the reference
line, cash, cheque) and is keyed in by hand. The roadmap's pay-without-
login decision (2026-07-17) is not yet real: there is no way for a
member to pay online, and the renewal emails CR-005 will send have no
pay-now link to carry — which is the main reason emails were sequenced
after Stripe.

Roadmap scope (CR-004): magic-link pay page, Stripe Checkout, webhook,
receipt email (bringing minimal SMTP config), journal add-on and
donation line.

Constraints already decided (roadmap Decisions table): Stripe-hosted
payment page (card data never touches the server, PCI SAQ A); webhook
records the Payment and activates the membership; login is never the
payment gate; unauthenticated visitors are never shown membership
status directly — the "lost my link" page emails the link to a
matching address instead; refunds happen in the Stripe dashboard and
are recorded manually; no subscriptions.

## Approach

### Schema (V3 migration — the first post-CR-001 schema change)

Exactly the "known later additions" the schema doc reserved for this
CR, plus one column:

```sql
CREATE TABLE renewal_token (
    renewal_token_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    membership_id    bigint NOT NULL REFERENCES membership ON DELETE RESTRICT,
    token_hash       text NOT NULL UNIQUE,   -- sha256 hex of the token; the raw token is never stored
    created_at       timestamptz NOT NULL DEFAULT now(),
    expires_at       timestamptz NOT NULL,
    used_at          timestamptz             -- first successful payment through this token
);
CREATE INDEX renewal_token_membership ON renewal_token (membership_id);

ALTER TABLE membership_period ADD COLUMN journal_price_cents integer
    CHECK (journal_price_cents IS NULL OR journal_price_cents >= 0);
```

- **Token**: 256-bit `SecureRandom`, base64url (43 chars), sent in the
  link; only its sha256 lands in the database, so a DB leak leaks no
  usable pay links. Lookup = hash the presented token, indexed match.
- **Not single-use**: an abandoned Checkout must not burn the link, and
  a paid-up page ("thanks, you're financial") is the friendly landing
  for re-clicks. `used_at` is bookkeeping, not a gate; `expires_at`
  (the day after the membership's `end_date`, so paying on the last day
  works — floored at 30 days out, so a link minted for a just-ended
  period isn't dead on arrival) is the gate.
- **Amended at implementation — mint is fresh-per-call, not idempotent**:
  the original text wanted one live token per membership with an
  idempotent mint, but that is unimplementable under hash-only storage
  (a stored sha256 cannot be turned back into the token to re-present
  it). Each mint therefore issues a fresh token and older unexpired
  tokens simply stay valid until expiry. Every property the idempotency
  was for survives: CR-005 can mint per-email safely (each email gets a
  live link, previously emailed links keep working), and a re-clicked
  link never dead-ends. The only cost is extra `renewal_token` rows.
- **Journal price is per-period config** (`journal_price_cents`,
  NULL = add-on not offered), alongside the per-period membership
  prices, editable via the existing period endpoints. CR-003's
  deferred "journal add-on pricing" lands here. A `payment_allocation`
  row of type JOURNAL is the record that a household bought it —
  there is still no publication table (schema doc: that graduates
  only if needed).

### Public API (guest-reachable — AuthFilter already passes guests)

New `PayResource` and `StripeWebhookResource`, registered in
`ApiApplication`, no `@RolesAllowed`:

| method | path | behaviour |
|---|---|---|
| GET | `/api/pay/{token}` | pay-page data: household display name, period name, membership type, `dueCents`, derived `paidCents`, status, `journalPriceCents` (null if not offered, or if a JOURNAL allocation already exists for this membership), society name. 404 for unknown or expired token — indistinguishable, and no other lookup exists (this is the only thing a token unlocks; the roadmap's "never shown membership status directly" rule is satisfied because holding the emailed token IS the authorisation) |
| POST | `/api/pay/{token}/checkout` | body `{journal: boolean, donationCents?: int ≥ 0}` → creates a Stripe Checkout Session and returns `{url}`. Line items computed **server-side**: balance due (`due − paid`, only if > 0), journal add-on at the period price if requested and offered, donation if given — the client never sends an amount for anything but the free-entry donation. Session: `mode=payment`, card only (keeps completion synchronous — no async payment methods, so `checkout.session.completed` is the only event that matters), currency AUD, `success_url`/`cancel_url` back to the pay page, metadata = `membershipId` + the allocation breakdown in cents. 409 when there is nothing to pay (balance ≤ 0 and no add-on selected) or the membership is CEASED |
| POST | `/api/stripe/webhook` | see below |
| POST | `/api/pay/lost-link` | body `{email}` → always 202 with the same body ("if that address matches a member, we've emailed the link") — no enumeration. Server side: match `email_address` case-insensitively (the schema doc warns one address can match several people — collect ALL of them), walk person → current household(s) → memberships in the current period, mint/fetch tokens, send one email listing each household's link and balance. No match, no current membership, or SMTP unconfigured → same 202, logged server-side |

### The webhook (the part that must be boringly correct)

`POST /api/stripe/webhook`:

1. Read the **raw request body** before any JSON parsing — Stripe's
   signature is over the exact bytes.
2. Verify the `Stripe-Signature` header against
   `STRIPE_WEBHOOK_SECRET` (stripe-java's `Webhook.constructEvent`,
   default 5-minute timestamp tolerance). Failure → 400. This is the
   entire authentication of the endpoint.
3. Ignore everything except `checkout.session.completed` with
   `payment_status == "paid"` → 200.
4. In **one transaction** (the CR-003 pattern — stores take the
   `Handle`, the resource owns the transaction): insert the `payment`
   (method STRIPE, `external_transaction_id` = the PaymentIntent id,
   `received_date` from the event timestamp, `recorded_by` =
   `stripe-webhook`, amount = the session's `amount_total`) with
   allocations from the session metadata, then run the existing
   status recompute for the touched membership (paid ≥ due →
   ACTIVE, `approved_date` coalesced — identical rule to manual
   payments, same code path).
5. **Idempotency is rule 12 doing its job**: a redelivered event hits
   the partial unique index on `external_transaction_id`, which the
   resource catches and answers 200 (already recorded, no-op). No
   separate processed-events table.
6. A verified-but-unprocessable event (metadata names a membership
   that doesn't exist, amount ≠ metadata sum) is logged loudly and
   answered 200 — Stripe retries non-2xx for days, and a retry cannot
   fix a malformed event. Amount-mismatch handling (should be
   impossible since we authored the session): record the payment with
   the metadata allocations plus an OTHER allocation for the
   difference, flagged in `payment.notes` — never silently drop money.
7. After the commit: send the receipt email (below) to the session's
   `customer_details.email`. Email failure never fails the webhook —
   the payment is recorded; the receipt is best-effort, logged.

Double-payment race (member pays online after the treasurer keyed the
bank transfer): both record; the membership shows overpaid exactly as
CR-003's overpayment rule intends; refund via the Stripe dashboard,
recorded as a negative payment. **CR-003 amendment**: the admin
payment endpoint's "STRIPE is never hand-entered" softens to "a
hand-entered STRIPE payment must be negative" (400 otherwise) — that
is the refund-recording path; positive STRIPE rows still only ever
come from the webhook.

### Admin API

| method | path | behaviour |
|---|---|---|
| POST | `/api/admin/memberships/{id}/pay-link` | mint a fresh token (see amendment above) → `{url, expiresAt}` where url = `PUBLIC_BASE_URL` + `/web/pay.html?t=` + token. This is how the treasurer gets a link to paste into a manual email today ("Copy pay link" in the membership dialog), and the primitive CR-005's merge fields will call |

Period create/update (`AdminPeriodsResource`) accepts the new optional
`journalPriceCents`; period GET returns it.

### Receipt email — minimal SMTP (deliberately less than CR-005)

New `Mail` helper (jakarta.mail via `org.eclipse.angus:angus-mail`),
env-configured: `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` /
`SMTP_PASSWORD` / `SMTP_STARTTLS` / `MAIL_FROM`, plus
`MEMBERROLL_SOCIETY_NAME` (the single-tenant rule: no "Yass" in code).
`SMTP_HOST` unset → mail disabled: receipts and lost-link sends are
skipped with a WARN (matrix rows still pass — the 202 contract doesn't
change). Two hardcoded plain-text templates (receipt: society name,
period, line amounts, "your membership is now active"; lost-link: the
link(s) and balance(s)). Templates-as-data, merge fields, send log,
segmenting, preferences — all CR-005; this CR ships only what a
payment receipt and a lost-link reply need. (Stripe's own dashboard
receipts were considered and rejected as the primary receipt: the
society's confirmation should say "you are financial for 2026–2027",
which Stripe cannot; the dashboard toggle can stay off.)

Dev compose gains a **Mailpit** service (SMTP + web UI for eyeballing
sent mail), on the project's 18xxx scheme: SMTP 18026, UI 18025 —
CR-005 will need it anyway.

### Public pay page

`web/pay.html` + `pay.js` (classic script, Pico baseline from CR-009,
no auth.js — this page never logs in). Reads `?t=` token:

- token valid, balance > 0: summary (household, period, type, amount
  due/paid), journal checkbox with price when offered, optional
  donation amount, **Pay now** → POST checkout → redirect to the
  returned Stripe URL;
- returned from Stripe with `?paid=1` (success_url): poll
  `GET /api/pay/{token}` a few times — the webhook usually lands
  within seconds — then "Payment received, you're financial for
  {period}" (or "processing — you'll receive a receipt shortly" if
  the poll times out); cancel_url returns to the form unchanged;
- balance ≤ 0 / already ACTIVE: the thanks state, no pay button;
  LAPSED shows the form (paying reactivates, per CR-003's recompute);
  CEASED: "contact the society";
- 404: "link not recognised or expired", with the lost-link form
  (email field → POST, always shows the neutral confirmation) inline
  on the same page.

No Stripe.js, no iframe, no external asset: the page's only contact
with Stripe is the redirect to the hosted Checkout URL, which is what
keeps card data off the server (SAQ A) and the war self-contained.

### Config summary (all env, the existing pattern)

| var | dev default | purpose |
|---|---|---|
| `STRIPE_SECRET_KEY` | — (test key `sk_test_…`) | Checkout session creation |
| `STRIPE_WEBHOOK_SECRET` | — (from `stripe listen`) | webhook signature verification |
| `PUBLIC_BASE_URL` | `http://localhost:18080/server` | pay links, Checkout success/cancel URLs |
| `SMTP_HOST` etc., `MAIL_FROM` | Mailpit / unset | receipts, lost-link |
| `MEMBERROLL_SOCIETY_NAME` | `memberroll dev` | email + pay-page branding |

Missing Stripe config → the checkout endpoint answers 503 with a clear
message and everything else works; the app must not fail to start over
unset Stripe keys (an instance can run manual-payments-only forever).

Production: the webhook URL is `https://<host>/server/api/stripe/
webhook` — Caddy already proxies `/server/*` to Tomcat, no Caddyfile
change; the endpoint is registered in the Stripe dashboard alongside
the live keys (that step, live keys, and SPF/DKIM stay in CR-008).

### New dependencies

- `com.stripe:stripe-java` (latest at implementation, API version
  pinned in code). Rejected: raw REST via `java.net.http` +
  jakarta.json — session creation would be easy, but hand-rolling
  webhook signature verification is exactly the subtle-crypto-bug
  surface a maintained SDK exists to own.
- `org.eclipse.angus:angus-mail` (jakarta.mail implementation).

### Rejected alternatives

- **Stripe.js / embedded Payment Element** — hosted Checkout keeps
  card data entirely off-page (SAQ A), needs no external JS, and the
  redirect UX is fine at this scale.
- **Single-use tokens** — an abandoned Checkout or a re-clicked email
  link must not dead-end a member; expiry + idempotent recording make
  reuse safe.
- **Storing the raw token** — hash-only costs one sha256 and removes
  pay-links-in-the-clear from every backup.
- **A processed-events table for webhook idempotency** — the CR-001
  partial unique index on `external_transaction_id` was built for
  this (rule 12); a second mechanism would just disagree with it.
- **Async payment methods (BECS debit etc.)** — card-only keeps
  `checkout.session.completed` synchronous and the state machine
  two-state; the society's non-card channel is bank transfer, which
  already works (CR-003).
- **Fee surcharging** — see open questions; the design assumes the
  society absorbs the ~1.7% + 30¢ (≈ $1.40 on a $65 household). If
  they decide otherwise, a surcharge line item is a contained
  amendment (it would ride as an OTHER allocation).
- **Auto-refund via API** — roadmap out-of-scope; dashboard + negative
  payment keeps the app out of the money-moving business.

## Verification plan

Scripted (extend `verify-matrix.sh`; fixtures `tmp/cr004-fixtures/`).
Two properties keep the matrix runnable offline: tokens can be seeded
directly (insert a known hash via psql), and webhook signatures can be
**self-signed** — the signature is HMAC-SHA256 with
`STRIPE_WEBHOOK_SECRET`, which the fixture script holds, so it can
construct valid `Stripe-Signature` headers over fixture payloads
without any Stripe account. Only checkout-session creation needs the
network: those rows run when `STRIPE_SECRET_KEY` is set (test mode)
and report SKIP otherwise.

| # | caller | call | expect |
|---|---|---|---|
| 1 | guest | GET /api/pay/{seeded token, PENDING_PAYMENT membership} | 200: due/paid/status/journal price correct |
| 2 | guest | GET /api/pay/{unknown token} | 404 |
| 3 | guest | GET /api/pay/{expired token} | 404, same body as #2 |
| 4 | guest | GET /api/pay/{token of fully-paid membership} | 200, balance 0, status ACTIVE |
| 5 | testadmin | POST /api/admin/memberships/{id}/pay-link | 200 `{url, expiresAt}`; psql: token_hash row, expires_at = period end |
| 6 | testadmin | POST same again | 200, same token (idempotent — url identical) |
| 7 | testuser / guest | POST /api/admin/memberships/{id}/pay-link | 403 |
| 8 | test-cli-noaud | POST /api/admin/memberships/{id}/pay-link | 401 |
| 9 | testadmin | PUT /api/admin/periods/{id} {journalPriceCents:1000} | 200; #1 re-run now shows journal offered |
| 10 | guest | POST /api/pay/{token}/checkout {journal:false} [needs key] | 200 `{url}` pointing at checkout.stripe.com |
| 11 | guest | POST /api/pay/{paid token}/checkout {} | 409 |
| 12 | guest | POST /api/pay/{token of CEASED membership}/checkout | 409 |
| 13 | guest | POST /api/stripe/webhook, valid self-signed checkout.session.completed (membership M, amount = balance) | 200; psql: payment row method STRIPE, external_transaction_id set, allocation MEMBERSHIP; M now ACTIVE; token used_at set |
| 14 | guest | POST identical payload again (redelivery) | 200; psql: still exactly one payment row |
| 15 | guest | POST same event, **bad signature** | 400; no payment row |
| 16 | guest | POST valid-signed but stale timestamp (> tolerance) | 400 |
| 17 | guest | POST valid-signed, metadata names nonexistent membership | 200; no payment row; error logged |
| 18 | guest | POST valid-signed with journal+donation breakdown | 200; psql: three allocations (MEMBERSHIP/JOURNAL/DONATION) summing to amount_total |
| 19 | guest | POST /api/pay/lost-link {email of member with unpaid membership} | 202; Mailpit shows one message containing the pay URL |
| 20 | guest | POST /api/pay/lost-link {unknown email} | 202, identical body; no mail |
| 21 | guest | POST /api/pay/lost-link {email shared by two people} | 202; mail lists each household's link once |
| 22 | testadmin | POST /api/admin/payments, **positive** amount, method STRIPE | 400 (webhook-only) |
| 23 | testadmin | POST /api/admin/payments, negative amount, method STRIPE ("refund recorded") | 201; membership recomputed (drops to PENDING_PAYMENT if under) |
| 24 | — | webhook + admin endpoints with Stripe env unset | checkout POST → 503 with message; everything else unchanged |

Browser walkthrough (dev stack + `stripe listen --forward-to
localhost:18080/server/api/stripe/webhook`, Stripe test mode):

1. Admin: set a journal price on the current period; open a
   membership, copy the pay link.
2. Open it logged-out (and once on a phone via the LAN IP): summary
   correct; tick journal, add a $5 donation; Pay now → Stripe test
   card `4242 4242 4242 4242` → redirected back → page flips to paid
   within the poll window.
3. Admin: membership now Paid; payment listed with three allocations;
   receipt visible in Mailpit (society name, period, amounts).
4. Cancel path: start a Checkout, click back/cancel → form intact,
   nothing recorded.
5. Lost-link: request with a member email → Mailpit mail → link works.
6. Refund: refund the test payment in the Stripe dashboard, record
   the negative STRIPE payment in the admin panel → status drops back
   to Unpaid.

Deploy "Local smoke" (deploy/README §6) applies before the production
push — this CR adds public endpoints (auth surface) even though Caddy
config is untouched.

## Open questions (to confirm with the society before/while implementing)

- Fee pass-on: design assumes absorbed (see rejected alternatives).
- Receipt wording, and whether the society also wants Stripe's own
  dashboard receipts on (design assumes off).
- Donation line on the pay page: free-entry amount assumed (no preset
  tiers).

## Results

Implemented 2026-07-18. Deviations from the proposal, all recorded above
in place: fresh-token-per-mint instead of idempotent mint (hash-only
storage makes idempotent re-presentation impossible); token expiry is
the day AFTER `end_date` so a last-day payment works; stripe-java
29.2.0 is used for Checkout-session creation and webhook signature
verification only — the webhook body itself is parsed with jakarta.json,
so nothing couples to the SDK's pinned API version.

### Scripted matrix

`server/verify-matrix.sh` extended with the CR4-* rows (the proposal's
rows 1–24, renumbered during implementation; run `grep CR4- verify-matrix.sh`
for the exact set). Offline run (no Stripe key; checkout rows SKIP),
2026-07-18: `PASS=265 FAIL=0`. Full run with a Stripe sandbox key and
`stripe listen` (checkout-session rows included — real session created,
URL on checkout.stripe.com), same day, run by Jason:

```
PASS=266 FAIL=0
```

Note for re-runs: the matrix shell's `STRIPE_WEBHOOK_SECRET` must match
the server's (the webhook rows self-sign) — the script now probes with
a signed no-op event and SKIPs with an explanatory note on mismatch
rather than failing bogusly.

Everything from the proposal's table is covered except rows 10–12
(checkout-session creation against real Stripe): those run automatically
when `STRIPE_SECRET_KEY` is set and were SKIPped in this run (no test
key in the dev environment); their offline flip side — checkout → 503
when unconfigured — passed (CR4-09u). Highlights verified:

- mint/pay-view/expiry: fresh token per mint, both live (CR4-01/02);
  unknown and expired tokens 404 with byte-identical bodies (CR4-06/07);
  guest/user/noaud on the mint endpoint 403/403/401 (CR4-04).
- journal add-on: per-period price via PUT (CR4-08), surfaces on the pay
  view, hides again once a JOURNAL allocation exists (CR4-18d).
- webhook: self-signed `checkout.session.completed` records a STRIPE
  payment with `external_transaction_id`, activates the membership, sets
  `used_at` (CR4-12); byte-identical redelivery no-ops via the rule-12
  unique index (CR4-13); bad signature and stale timestamp 400 with
  nothing recorded (CR4-14/15); other event types and unpaid sessions
  ignored (CR4-16); unknown-membership metadata → logged, 200, nothing
  recorded (CR4-17); journal+donation lands as three allocations summing
  to `amount_total` (CR4-18).
- receipt email in Mailpit with "financial for 2025-2026" (CR4-19).
- lost-link: 202 with identical body for match/no-match/garbage
  (CR4-20/21); mail carries the pay URL and balance; no mail for an
  unknown address; an address shared by two people gets both households'
  links in one mail (CR4-22).
- CR-003 amendment: positive STRIPE hand-entry 400; negative STRIPE
  (dashboard-refund recording) 201 and the membership demotes (CR4-23/24).

### Post-implementation review round

A multi-angle code review of the diff surfaced 10 findings; all were
fixed and the matrix re-run (the +5 rows over the first run are the new
regression checks). The fixes, several of which amend the design above:

- **Webhook robustness**: absent-vs-JSON-null handling for
  `payment_intent` / `amount_total` / `data` / `created` (each previously
  a 500 → Stripe-retry-loop on a signature-valid but odd event);
  `amount_total ≤ 0` and a missing session id are now explicit
  unprocessable-200s; a missing *money* metadata key (truncation) is
  unprocessable rather than silently zeroed (CR4-17c) — only `tokenId`,
  pure bookkeeping, stays optional. Duplicate detection now keys on
  SQLSTATE 23505 alone (constraint-name string matching would break
  under Postgres message localization).
- **STRIPE hand-entry**: the negative-amount rule now applies per
  allocation line, not just the total (a negative-total entry could
  otherwise smuggle in a positive MEMBERSHIP allocation — CR4-23b), a
  zero amount is a clean 400 (CR4-23c), and V4 adds the structural CHECK
  `payment_method <> 'STRIPE' OR amount_cents < 0 OR
  external_transaction_id IS NOT NULL` so no future entry point can mint
  webhook-invisible STRIPE money. The admin panel's Reverse button no
  longer coerces STRIPE→OTHER when reversing a webhook payment.
- **Journal add-on**: "already bought" is now SUM > 0, not
  EXISTS(positive line), so a refunded journal becomes purchasable again
  (CR4-18e/f).
- **Tokens**: mint's expiry is `GREATEST(end_date + 1, now() + 30 days)`
  — a link minted for a just-ended period is no longer dead on arrival.
- **Lost-link**: also matches next-period memberships whose
  `renewal_open_date` has arrived (rollover season is exactly when the
  form is needed); the lookup/mint/send now runs on a mail thread after
  the 202 (response latency no longer an enumeration oracle) behind a
  10-minute per-address cooldown (no email-bombing / unbounded token
  minting); the query moved into `RenewalTokenStore` sharing
  `MembershipStore.PAID_SQL`, the single rule-6 paid-ness definition.
- **Mail**: 10-second connect/read/write SMTP timeouts (jakarta.mail
  defaults are infinite — a half-open relay would pin Tomcat threads),
  and the webhook queues the receipt asynchronously so SMTP can never
  push the 200 past Stripe's webhook timeout.
- **Guest checkout**: malformed `donationCents` now 400s instead of
  500ing; `PUBLIC_BASE_URL` unset logs a loud one-time warning (emailed
  links would otherwise silently point at localhost in production).

### Browser walkthrough

Completed by Jason 2026-07-18 against a Stripe sandbox with
`stripe listen` (procedure: [004-stripe-walkthrough.md](004-stripe-walkthrough.md)).
All six steps behaved: journal price + Copy pay link; Checkout with the
4242 test card (redirect back, page flipped to paid within the poll
window); membership Paid with three allocations and the receipt in
Mailpit; cancel path left the form intact with nothing recorded;
lost-link email with a working link; dashboard refund recorded via
Reverse (negative STRIPE) dropping the membership back to Unpaid.

Two findings during the walkthrough, both fixed in this CR:

- Errors raised inside modal dialogs (e.g. the 409 for a duplicate
  period name) were written to the header message line, which the
  dialog backdrop hides — `say()` now mirrors messages into the open
  dialog (admin.js/admin.css).
- The lost-link email initially omitted the member's next-period
  membership: the seeded `renewal_open_date` placeholder (1 Aug) was
  later than the society's actual 1-July rule, so the renewal window
  hadn't opened. Data fix (set renewal_open_date to 1 July), not a code
  fix — but it surfaced that there is no UI to edit an existing
  period's dates (see follow-ups). Steps that don't touch Stripe were exercised at the API
level by the matrix; the pay page's states (form / paid-up / ceased /
lost-link) are driven by the same `GET /api/pay/{token}` responses the
matrix asserts. Run the walkthrough before flipping this CR to VERIFIED.

## Follow-ups / amendments

- CR-005 consumes the pay-link mint endpoint for merge fields and
  replaces the hardcoded email templates with real ones + send log.
  Note the amended mint semantics: every call is a fresh token (older
  links stay valid) — fine for per-email minting.
- CR-008 registers the production webhook endpoint, swaps in live
  keys, and covers SPF/DKIM for the from-address. Production env to set:
  `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `PUBLIC_BASE_URL`,
  `SMTP_*`, `MAIL_FROM`, `MEMBERROLL_SOCIETY_NAME`.
- Admin UI cannot edit an existing period's dates (renewal-open,
  cutoff) — surfaced by the walkthrough's renewal_open_date fix needing
  psql. Small addition; fits CR-010's admin polish.
- Confirm the real 2026-2027 `renewal_open_date` (1 July per the
  society's joining rule) when the production period is created.
