# CR 021: Mail sandbox — redirect all outgoing email while testing

Status: IMPLEMENTED + VERIFIED (2026-07-23)

## Problem

The demo instance (members.yasshistory.org.au) is being tested with the
society's **real member data** — real email addresses — against the
**live** Exchange Online relay and the Stripe sandbox. Any test that
touches a mail surface risks emailing an actual member: a segment send,
a receipt, a card, an approval notice. Worse, some sends are
**guest-triggered** — the CR-004 lost-link form and the CR-007
application confirmation can be fired by anyone visiting the public
pages, with no admin in the loop at all. "Be careful" is not a
control; the system needs a positive guarantee that, while testing, no
message leaves for a member's real mailbox.

At the same time the relay itself is part of what is being tested
(Exchange SMTP AUTH, SPF/DKIM, the CR-014 page) — so the mechanism
should keep exercising the real SMTP path, not bypass it.

## Approach

One new optional field in the CR-014 `smtp_settings` blob, surfaced on
the mail-settings page:

> **Sandbox: redirect all outgoing mail to** `[address]`

Empty (the default, and the state of every existing install) = live
mail, byte-for-byte today's behaviour. Set = **every** message the app
sends is delivered to that one address instead of its real recipient,
with the original recipient preserved in plain sight:

- subject prefixed `[SANDBOX for jo@example.com] <original subject>`;
- body prefixed with a line `SANDBOX REDIRECT — this message was
  addressed to: jo@example.com` and a blank line;
- everything else (body text, Reply-To, attachments — the CR-017 card
  PNG included) unchanged.

### Why this shape

- **One choke point.** The rewrite lives in `Mail.doSend`, which every
  send in the app already funnels through: CR-004 Stripe receipts and
  lost-link mail, CR-005 segment sends (including the async worker and
  resume), CR-012 receipts, CR-017 cards (the attachment overload),
  CR-007 confirmations / alerts / approval and rejection notices, and
  the settings page's own test button. No per-feature toggles, and the
  guest-triggered surfaces are covered automatically. A future mail
  surface inherits the guarantee for free.
- **Per-send resolution (CR-014) makes it instant.** `Mail` resolves
  its settings on every send, uncached — saving the field applies to
  the very next message, clearing it goes live again, no restart.
- **The real SMTP path still runs.** Messages go through the configured
  relay (Exchange today) to the redirect address — so the thing under
  test keeps being tested. The redirect target can be a tester's own
  mailbox (read on a phone, attachments and all) or a Mailpit if one is
  running (see Option B below).
- **PAGE-only, deliberately.** The field rides the `smtp_settings`
  blob; the ENV fallback path stays byte-for-byte CR-004/CR-014
  behaviour (dev talks to Mailpit anyway and needs no sandbox). An
  environment-level backstop the UI cannot override — for pinning the
  demo box into sandbox at the deployment layer — is a separate,
  compatible follow-up (the "C hybrid": `MAIL_REDIRECT` in the prod
  `.env` taking precedence over the page). It may follow as its own
  small CR; nothing here precludes it.

### Visibility — the forget-risk cuts both ways

Forgetting the sandbox is on at real go-live means members silently get
nothing; forgetting to turn it on while testing means members get test
mail. Both failure modes are countered with loud, ambient visibility:

- the mail-settings page shows the field with a warning banner while
  set ("⚠ SANDBOX — all outgoing mail is redirected to …");
- `GET /api/admin/mail-settings` gains `redirectTo`, and **every admin
  page** shows a compact banner in the shared header while the sandbox
  is active (the pages share `admin.js`; one extra admin-gated GET per
  page load). An admin cannot use the panel without seeing it.
- the test button honours the redirect like every other send (the
  marker names the address the admin typed) — so the sandbox path
  itself is provable from the page.

### Semantics worth pinning

- Validation: the field must contain `@` when set; blank clears it (no
  absent-means-keep subtlety — unlike the password, the page always
  sends the field; live-vs-sandbox must never be ambiguous).
- The CR-005 send log keeps recording the **real** recipients: the log
  records intent, the transport was redirected. Same for
  `email_send_recipient.renewal_token_id` — tokens are still minted;
  they simply arrive at the sandbox address. This is correct (testing
  exercises the true code path) and is stated in the user manual.
- `Mail.enabled()` is unchanged — sandbox is a *destination* concern,
  not an availability one; every existing 503/banner gate behaves
  exactly as today.

### Out of scope, stated loudly: Keycloak's own mail

Keycloak's forgot-password email is sent by the **realm's**
`smtpServer` config, not the app's `Mail` — this CR cannot redirect
it. While testing with provisioned members, the realm's SMTP config
must be pointed at the same sandbox target (or Mailpit) via the
tunnelled admin console, and back again afterwards. This goes in the
demo/testing runbook as a paired manual step; forgetting it means a
member testing "Forgot Password" emails a real address.

## Option B today — Mailpit capture with no code change

Independent of this CR, an operator can get full capture on the box
right now; with this CR implemented these same steps just become one
possible redirect *target*. Steps (also destined for the runbook):

1. Add the `mailpit` service to the instance's compose file — copy the
   service block from the deploy smoke override (`server/deploy/`,
   CR-008; the service **name** `mailpit` matters — it is the hostname)
   with no published ports beyond loopback, then `docker compose up -d`.
2. On the admin **Mail settings** page: Host `mailpit`, Port `1025`,
   Security `None`, **Username blank** (no AUTH attempted; the stored
   Exchange password is untouched — CR-014's absent-password-keeps rule
   means it survives for the switch back), Save.
3. Point the realm's `smtpServer` at `mailpit:1025` too (tunnelled
   console) — the Keycloak caveat above.
4. Read captured mail via an SSH tunnel to the Mailpit UI port (8025).
5. To go live again: re-save the real host/port/security/username with
   the password field left empty (keeps the stored secret), and restore
   the realm's `smtpServer`.

Caveats that motivated Option A anyway: the "toggle" is retyping relay
settings (easy to half-do), nothing but the hostname says which mode
the system is in, and Exchange itself stops being exercised.

## Alternatives considered

- **Option D — dry-run preview, never touching SMTP** (composed text in
  a pop-up / an in-app outbox). Rejected as the general mechanism: the
  useful previews already exist where preview is the product (CR-012's
  receipt GET returns the canonical text; CR-017's card info endpoint);
  generalising means either per-surface preview dialogs across five
  features or an in-app captured-outbox viewer — which is
  re-implementing Mailpit inside the webapp — and it stops testing
  exactly the parts most worth testing now (the relay, CR-005
  abort/resume, multipart attachments, the async sender).
- **Suppress instead of redirect** (sandbox = drop mail, log only).
  Rejected: the tester loses the evidence (message content, rendering,
  attachments), and a silently-dropping mail system is indistinguishable
  from a broken one — the CR-014 test button would "succeed" while
  proving nothing.
- **Scrubbing the imported data** (replace member emails with test
  addresses on the demo box). Rejected: testing against the real
  register is the point of the exercise, the scrub is irreversible
  short of re-import, and it does nothing for the next testing round.
- **ENV-only toggle** (no page field). Deferred, not rejected — as the
  belt-and-braces backstop it is the C hybrid follow-up; as the *only*
  control it fails the committee's established preference for
  page-settable operational config (CR-014, CR-007 settings) and needs
  SSH + restart to flip.

## Config

No migration (one more key in the existing `smtp_settings` JSON blob),
no new env, no realm change, no new endpoint (rides
`GET`/`PUT /api/admin/mail-settings`). UI: the field + banner on
`admin/mail-settings.html`, the ambient banner in the shared admin
header. Runbook: the Keycloak `smtpServer` pairing note and the Option
B steps.

## Verification plan

Matrix rows (CR21-*; dev stack, Mailpit as the relay — the redirect
target is a second Mailpit-visible address, so both "arrived at
sandbox" and "nothing at the real address" are assertable):

| # | case | expect |
|---|---|---|
| CR21-01 | PUT mail-settings with redirectTo lacking `@` | 400 |
| CR21-02 | PUT with `redirectTo: sandpit.$$@example.com`; GET echoes | 200/200, `redirectTo` present |
| CR21-03 | CR-012 receipt email to a fixture member | mail arrives at sandpit address only; subject starts `[SANDBOX for <member addr>]`; body first line names the member addr; member addr has 0 messages |
| CR21-04 | CR-017 card email | redirected AND the PNG attachment still present |
| CR21-05 | CR-005 segment send (2 recipients) | both messages at the sandpit address; `email_send_recipient` rows still carry the real addresses + SENT |
| CR21-06 | guest lost-link request for a fixture member | the pay-link mail lands at the sandpit address, not the member's |
| CR21-07 | mail-settings test button while sandboxed | test mail redirected, marker names the typed address |
| CR21-08 | PUT with blank redirectTo; re-send CR21-03 | mail goes to the real (fixture) address, no marker — live restored |
| CR21-09 | password survives sandbox round-trip | `passwordSet` still true after CR21-02→08 (no retyping) |

Browser walkthrough: field round trip on the mail-settings page; the
warning banner on that page while set; the ambient banner visible on a
second admin page (e.g. the register) and gone after clearing.

The prior mail suite (CR-004/005/012/014/017/007 rows) re-run green
with the blob field absent — proving the empty-field path is
byte-for-byte unchanged, the CR-014 ENV-parity discipline applied one
layer up.

## Results

Implemented 2026-07-23 exactly as proposed: the rewrite is the first
thing `Mail.doSend` does (subject prefix, body first line, then the
recipient swap — everything downstream, including the CR-017 multipart
attachment path, is untouched); `redirectTo` rides the `smtp_settings`
blob and the `Settings` record (PAGE parse only — `envSettings` pins it
null), validation is the same parseable-email check as `from`/`replyTo`
(strictly a superset of the proposed "must contain `@`"), and the
GET/PUT/DELETE response gained the field. UI: the field + inline warning
on `admin/mail-settings.html`, and the ambient header banner via
`admin.js` `renderSandboxBanner`/`refreshSandboxBanner` (one
non-awaited, silent-on-failure admin GET per page load) with a
`.sandbox-banner` style that carries its own background in both themes.

Matrix: 37 CR21-* rows added to `server/verify-matrix.sh` (self-cleaning
— the block ends by deleting the row, restoring ENV). Two adaptations
from the proposed plan, both recorded here deliberately:

- **Dev Mailpit advertises no SMTP AUTH**, so a stored username would
  make every real send fail before reaching the redirect logic. The
  send rows (CR21-03..08) therefore run an auth-free blob, and CR21-09's
  "password survives the round trip" is proven beside the echo rows
  instead: a re-save carrying `redirectTo` with the password ABSENT
  keeps `passwordSet=true` (CR21-02/09) — the no-retyping claim, which
  is what the row was for.
- The CR21-05 fixture period must price **every** membership type
  (SINGLE/HOUSEHOLD/LIFE — the PeriodStore rule); the first draft priced
  only SINGLE and 400'd.

Also: lost-link (CR21-06) runs before the fixture payment — a paid
membership has no payable balance to lose a link to.

Runs (dev stack, 2026-07-23): full matrix twice, `PASS=843 FAIL=1` both
times, where the 1 is the pre-existing testuser-listing Keycloak flake
(row 27b) — all 37 CR21 rows green twice, and every prior mail row
(CR4/5/12/14/17/7) green with the blob field absent, proving the
empty-field path byte-for-byte unchanged. Highlights: redirected receipt
subject `[SANDBOX for <member>] … payment receipt` with the body's first
line naming the member and the real address at 0 messages (CR21-03);
card PNG attachment intact through the redirect (CR21-04c); segment send
delivering both messages to the sandbox while `email_send_recipient`
keeps the real addresses + SENT (CR21-05); guest lost-link redirected
(CR21-06); the test button's marker naming the typed address (CR21-07);
live restored with no marker after a blank re-save (CR21-08).

Browser walkthrough (`tmp/cr021-fixtures/cr021-walkthrough.js`,
Playwright): 11/11 — field round trip, inline warning while set, ambient
banner on the mail-settings page AND on the Reports page, both gone
after clearing, DELETE restores ENV. Screenshots confirm the banner
placement under the shared header.

Docs: user manual gained "Mail settings and the testing sandbox"
(including the send-log-records-intent semantics), deploy README gained
§7 (the sandbox runbook: the Keycloak `smtpServer` pairing step + the
Option B on-box Mailpit capture steps), README feature list updated.

## Follow-ups / amendments

- The **C hybrid** — an environment-level `MAIL_REDIRECT` backstop the
  UI cannot override, for pinning the demo instance into sandbox at
  the deployment layer — may follow as a separate small CR.
- ~~Runbook additions at implementation time: the Keycloak `smtpServer`
  pairing step, and the Option B Mailpit-on-the-box steps above.~~
  DONE — `server/deploy/README.md` §7.
