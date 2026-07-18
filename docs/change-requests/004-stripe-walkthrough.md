# CR-004 Stripe walkthrough — step-by-step (sandbox)

The Stripe-dependent half of CR-004's verification plan
([004-stripe.md](004-stripe.md)): a real Checkout payment against a
Stripe **sandbox**, end to end through the webhook, receipt and refund.
Everything here uses fake cards and moves no money.

Use your existing Stripe account and **switch to sandbox** (the current
name for what used to be "test mode") — do not create a second account.

## 1. Get your sandbox secret key

1. In the Stripe web UI, switch to your sandbox.
2. Open **Developers** (the dev toolbar at the bottom) → **API keys**.
3. Reveal and copy the **Secret key** — it starts `sk_test_…`. (The
   publishable `pk_test_…` key is not needed; the server never uses it —
   the page's only Stripe contact is the redirect to hosted Checkout.)

## 2. Install and connect the Stripe CLI

```bash
yay -S stripe-cli   # AUR on Manjaro; or the binary from github.com/stripe/stripe-cli/releases
stripe login        # browser pairing — make sure it shows the SANDBOX before confirming
```

If the pairing lands on the wrong account/sandbox, skip `stripe login`
entirely and pass the key directly: `stripe listen --api-key sk_test_…`.

## 3. Start the webhook forwarder (keep it running)

```bash
stripe listen --forward-to localhost:18080/server/api/stripe/webhook
```

The first line printed is `Your webhook signing secret is whsec_…` —
copy it. It stays stable for ~90 days, so restarts reuse the same value.
Leave this terminal running for the whole walkthrough; it shows each
event being forwarded and the server's response code.

## 4. Start the dev stack with the real keys

```bash
(cd server && docker compose up -d)   # postgres + keycloak + mailpit

# stop any already-running cargo first (Ctrl-C). If it was killed
# uncleanly, also: rm -rf server/target/cargo
STRIPE_SECRET_KEY=sk_test_… \
STRIPE_WEBHOOK_SECRET=whsec_… \
SMTP_HOST=localhost SMTP_PORT=18026 MAIL_FROM=noreply@memberroll.dev \
MEMBERROLL_SOCIETY_NAME="Yass & District Historical Society" \
    mvn -pl server cargo:run
```

`STRIPE_WEBHOOK_SECRET` is the one `stripe listen` printed — **not**
the `whsec_devmatrix` value used for the offline matrix rows.

## 5. The walkthrough

1. **Setup** — admin panel (`testadmin`/`testadmin`), Renewals: check
   the current period has a **Journal add-on price** (the field next to
   the period selector; e.g. $10). Create a throwaway test household +
   membership so you're not paying against real member data, open it
   with **Manage**, click **Copy pay link**.
2. **Pay** — open the link in a private/logged-out window. Check the
   summary (household, year, due/paid/balance), tick the journal, enter
   a $5 donation, **Pay now**. On Stripe's page use card
   `4242 4242 4242 4242`, any future expiry, any CVC, any
   name/postcode. You are redirected back and the page flips to "you
   are financial for …" within a few seconds (the `stripe listen`
   terminal shows `checkout.session.completed` answered `200`).
3. **Verify the books** — admin: the membership is now Paid; the
   payment lists three allocations (MEMBERSHIP / JOURNAL / DONATION);
   the receipt email is visible in Mailpit at <http://localhost:18025>.
4. **Cancel path** — on a *different* unpaid membership, copy a link,
   click Pay now, then use Stripe's back arrow instead of paying. The
   form is intact and nothing is recorded.
5. **Lost link** — open the pay page with a bad token
   (`…/web/pay.html?t=junk`), submit the email of a member with an
   unpaid membership, find the mail in Mailpit, confirm its link works.
   (10-minute per-address cooldown: a repeat within that window
   deliberately sends nothing.)
6. **Refund** — in the sandbox dashboard, Payments → refund the step-2
   payment. Then in the admin panel click **Reverse** on that payment
   (it records the negative STRIPE entry) → status drops back to
   Unpaid.

Optional phone check: restart cargo with
`PUBLIC_BASE_URL=http://<your-LAN-IP>:18080/server` added, re-mint the
link, open it on the phone. The pay page needs no Keycloak, so none of
the issuer/redirect-URI LAN configuration applies.

## 6. Close out

Run the matrix with **both** Stripe variables exported, using the same
values cargo is running with — the webhook rows self-sign their fixture
events, so the script's `STRIPE_WEBHOOK_SECRET` must match the server's
or every webhook row fails 400 (the script detects the mismatch and
SKIPs with a note, but matched secrets actually run them):

```bash
STRIPE_SECRET_KEY=sk_test_… STRIPE_WEBHOOK_SECRET=whsec_… server/verify-matrix.sh
```

The CR4-09 checkout rows that SKIP offline now run against real Stripe.
If everything above behaved, record the results in
[004-stripe.md](004-stripe.md) and flip its Status to VERIFIED.
