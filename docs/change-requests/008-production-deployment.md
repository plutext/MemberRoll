# Change Request 008: Production Deployment — go-live for the Yass society

**Status:** Proposed
**Date:** 2026-07-19
**Builds on:** the `server/deploy/` assets inherited from webapp-template
(themselves extracted from TurbinePreview CR-037, where the topology —
Caddy TLS terminator, path-based Keycloak under `/auth`, single issuer,
tunnel-only admin console, hairpin-free `$DOMAIN` alias — was designed,
smoke-tested locally and then verified on a real instance);
[001](001-membership-register-data-layer.md) (the register, Flyway,
the `memberroll` database the prod compose already provisions),
[002](002-member-import.md) (the CSV import the real member list will go
through), [003](003-renewals-and-manual-payments.md) (periods, prices),
[004](004-stripe.md) (`STRIPE_*`, `PUBLIC_BASE_URL`, the webhook),
[005](005-segment-email.md)/[014](014-smtp-settings.md) (outbound mail —
in production configured from the admin Mail settings page, Exchange
Online target), [006](006-member-self-serve.md) (Keycloak's own SMTP for
forgot-password — the self-serve first-login path),
[013](013-committee-register.md) (the committee slate is go-live data).
**Feeds:** [007](007-public-application-form.md) (real applicants need a
reachable form), [011](011-constitution-register-compliance.md) (the
register exports matter once the register is the society's real one).

## Problem

Everything CR-001…CR-014 built runs only on the dev machine. The society
is ready to move off the spreadsheet-and-bank-statement process, and the
app has reached the point where the remaining CRs (007, 011 stages) are
additive rather than prerequisite — admin register, renewals, manual and
Stripe payments, receipts, segment email, self-serve, committee register
and configurable SMTP are all Verified.

The deploy assets are *nearly* ready: the topology and scripts were
proven end-to-end in TurbinePreview, and the MemberRoll copies are
already renamed (realm `memberroll`, `/opt/memberroll`, the app database
+ init script, `KEYCLOAK_ADMIN_URL` bypass, master-realm `frontendUrl`
pin). But they were last touched at CR-001 (`01d1402`) and an audit for
this CR found they have drifted behind the application by thirteen CRs:

1. **`compose.yml` gives Tomcat none of the post-CR-001 env.**
   `STRIPE_SECRET_KEY` / `STRIPE_WEBHOOK_SECRET` (CR-004),
   `PUBLIC_BASE_URL` (CR-004 — without it every emailed pay link points
   at `http://localhost:18080`, failing only in members' inboxes; the
   code warns but production must never rely on the default) and
   `MEMBERROLL_SOCIETY_NAME` (mail sender display) are all absent.
2. **`backup.sh` dumps only the `keycloak` database.** The `memberroll`
   database — the financial record, the reason "backups" is roadmap
   in-scope item 9 — is not dumped at all. (Template vestige: at
   CR-001-time TurbinePreview's second database had a different name and
   the rename pass caught the compose file but not the backup script.)
3. **The realm render leaves the dev `smtpServer` block in place.** The
   checked-in realm points Keycloak's own mail at compose-internal
   `mailpit:1025` (CR-006, dev). That host does not exist in the
   production stack, so forgot-password — the *only* first-login path
   for provisioned self-serve accounts — would fail silently.
4. **`MEMBERROLL_DATA` / `data/store` is a vestige.** Nothing in the
   application reads it (TurbinePreview's blob store; MemberRoll's state
   is wholly in Postgres). It costs a mount, an env var and an empty
   nightly tarball, and misleadingly implies file state exists.
5. **The production topology has never run this war.** The local smoke
   (deploy README §6) was last exercised against the CR-001 war; V2–V7
   migrations, the JWKS/admin-REST split, and everything mail/Stripe
   shaped have never been proven on the prod compose. And
   `verify-matrix.sh` hardcodes `http://localhost:$PORT`, so the full
   537-row matrix cannot currently point at the smoke stack at all.

Beyond closing those gaps, going live needs the things no script can
generate: a domain, live Stripe keys and a registered webhook endpoint,
the society's Exchange Online mailbox, and the real member list.

## Objective

The Yass & District Historical Society instance in production:

- one EC2 instance, one domain, HTTPS everywhere, single Keycloak issuer
  (the CR-037 topology, unchanged);
- live Stripe Checkout end-to-end (magic link → payment → webhook →
  PAID → receipt) with real money verified and refunded;
- outbound mail through the society's Exchange Online mailbox via the
  CR-014 admin page, with SPF/DKIM passing; Keycloak forgot-password
  mail through the same relay;
- the real member register imported and reconciled against the
  society's spreadsheet;
- nightly backups that actually cover the financial records, plus EBS
  snapshots;
- the dev loop and its 18xxx-port stack untouched.

## Design

### 1. Close the drift (repo changes)

**`compose.yml`** — the tomcat service gains:

```yaml
PUBLIC_BASE_URL: https://${DOMAIN}/server
MEMBERROLL_SOCIETY_NAME: ${MEMBERROLL_SOCIETY_NAME:-}
STRIPE_SECRET_KEY: ${STRIPE_SECRET_KEY:-}
STRIPE_WEBHOOK_SECRET: ${STRIPE_WEBHOOK_SECRET:-}
```

`PUBLIC_BASE_URL` is derived from `DOMAIN` in the compose file itself —
it is not independent configuration and giving it its own `.env` line
would only create a way for the two to disagree. The Stripe pair
defaults to empty, and `Mail.env`'s blank-is-unset idiom (the CR-004
convention every optional subsystem uses) makes an empty value identical
to absent: checkout and webhook answer 503 until the live keys are
filled in, and nothing else is affected. No `SMTP_*`/`MAIL_*` env in
production — the CR-014 page is the production mail configuration, and
leaving the env fallback empty means `Mail.resolve()` reports `PAGE` or
`NONE`, never a half-configured relay.

**`deploy.sh`** — the first-run `.env` template gains
`MEMBERROLL_SOCIETY_NAME=` and the two empty `STRIPE_*` lines with a
comment saying where each value comes from (§3); the realm render
additionally **deletes the `smtpServer` block** when
`KEEP_TEST_FIXTURES` is not set (the local smoke keeps it — Mailpit
exists there, §5). Production Keycloak therefore imports with no mail
config; the go-live runbook (§6) configures the real relay through the
tunnel console. This is a deliberate exception to the mirror-back
discipline: the relay credentials are environment data like the web
client's redirect URIs (which the render already replaces), not realm
design — the repo JSON keeps the dev Mailpit block.

**`backup.sh`** — dumps **both** databases:

```bash
docker compose exec -T postgres pg_dump -U keycloak keycloak   | gzip > "backups/keycloak-$ts.sql.gz"
docker compose exec -T postgres pg_dump -U memberroll memberroll | gzip > "backups/memberroll-$ts.sql.gz"
```

(the `memberroll` role owns its database; `postgres-init/` created it
with LOGIN, and `pg_hba` inside the container trusts local socket use —
verified in the smoke). The store tarball goes away with the vestige
below. Retention stays 14 nights on-box + EBS snapshots for instance
loss; additionally, the runbook schedules one **pre-rollover archive**
per year (a copy of that night's `memberroll` dump moved aside and never
pruned) — the register at year boundary is the AGM/audit artefact worth
keeping indefinitely, and it costs one file per year.

**`MEMBERROLL_DATA` removed** — the env var and the `./data/store`
mount leave `compose.yml`, the store tarball leaves `backup.sh`, and
`deploy.sh` stops creating `data/store`. MemberRoll's state is two
Postgres databases; the deploy assets should say so.

**`verify-matrix.sh`** — `API` and `KC` become overridable
(`API_BASE`/`KC_BASE` env, defaulting to the current localhost values)
and the curl invocations gain an optional `CURL_OPTS` (for
`-k --resolve smoke.localhost:443:127.0.0.1`), so the full matrix can
run against the smoke stack (§5). No check changes; dev invocations are
byte-identical.

**`compose.smoke.yml`** — gains a Mailpit service and points the smoke
tomcat's `SMTP_*`/`MAIL_*`/`STRIPE_WEBHOOK_SECRET` env at it, mirroring
the dev-matrix env in CLAUDE.md. This is smoke-only config layered over
the production compose — the production file stays clean of mail env —
and it is what lets the CR-004/CR-005/CR-012/CR-014 matrix rows run
against the production topology. (The CR-005 abort/resume rows
stop/start the Mailpit container; same trick, different compose file.)

### 2. Instance and domain

Unchanged from the proven CR-037 recommendations, restated for the
record: **`t4g.medium`** (2 vCPU Graviton, 4 GiB — two JVMs need the
headroom; `t4g.small` was considered and rejected there for exactly this
stack), 30 GiB gp3, **Ubuntu 24.04 LTS arm64**, 2 GiB swap,
**`ap-southeast-2`** (Yass is in NSW), Elastic IP, security group
80+443 open / 22 admin-IP-only. Rough cost ≈ US$40/month on-demand.
`server/deploy/README.md` §1 is the provisioning document and
verification item 3 executes it verbatim.

**Domain — decided 2026-07-19: `members.yasshistory.org.au`** — a
subdomain of the society's existing domain: one A record at their DNS
host pointing at the Elastic IP, no new domain to register or renew,
and members see a name they already trust. Everything remains
`$DOMAIN`-parameterized regardless.

### 3. Stripe: live mode

- Live keys from the society's Stripe account dashboard:
  `STRIPE_SECRET_KEY` (an `sk_live_…` restricted key is preferable —
  write access to Checkout Sessions, read to nothing else it doesn't
  need) into `.env`.
- A live **webhook endpoint** registered in the dashboard at
  `https://<domain>/server/api/stripe/webhook`, subscribed to
  `checkout.session.completed`; its signing secret is
  `STRIPE_WEBHOOK_SECRET`. The endpoint is guest-reachable through
  Caddy already (`/server/*`); its signature check over the raw bytes is
  its entire auth (CR-004), which is exactly why the signing secret must
  be the live endpoint's own.
- Keys live in `.env` (mode 600) only — never the repo, never compose.
- Verification pays real money once (smallest single membership) with a
  real card and refunds it from the dashboard, recording the negative
  STRIPE payment through the admin panel — which exercises the CR-003
  correction path against production data on day one, deliberately.
- Whether the society passes the Stripe fee on to the payer stays an
  open roadmap question — it is price configuration (CR-003 period
  setup), not deployment, and does not block this CR.

### 4. Mail: Exchange Online, SPF/DKIM, and Keycloak's own sender

Two senders, one relay:

- **The app** (receipts, segment email, lost-link) is configured at
  runtime from the CR-014 **Mail settings page**: the Microsoft 365
  preset (`smtp.office365.com`, 587, STARTTLS), the society's mailbox —
  decided 2026-07-19: **`secretary@yasshistory.org.au`** — as
  username/From, its password stored server-side. Nothing to deploy;
  the page's test-send (with its verbatim-SMTP-error surface) is the
  bring-up tool. Prerequisite the society must arrange: **SMTP AUTH
  enabled on that mailbox** (tenant security defaults disable it;
  Microsoft supports basic-auth SMTP submission through Dec 2026 —
  CR-014 recorded XOAUTH2 as the follow-up).
- **Keycloak** (forgot-password — the CR-006 first-login path — and
  self-registration verify-email) gets the same relay/mailbox entered
  once in the realm's Email settings through the tunnel console, after
  import (§1's render strips the dev block). Realm SMTP config is
  Keycloak-database state like the rotated client secret — the
  mirror-discipline exception is documented in the deploy README.
- **SPF/DKIM need no new DNS records**: sending from the society's own
  M365 mailbox through the tenant's submission endpoint means Microsoft
  signs and the existing tenant SPF covers it. The From address = the
  authenticated mailbox (Exchange enforces alignment anyway).
  Verification checks `Authentication-Results` on a received message
  rather than trusting this paragraph.

### 5. The production topology finally meets the current war (local smoke, upgraded)

Before any instance exists, the deploy README §6 smoke runs with the
CR-014-era war and the §1 changes: Flyway V1–V7 migrating a fresh
`memberroll` database under the prod compose, JWKS via the `$DOMAIN`
alias, the admin-console tunnel path — and then, the new capability,
**the full `verify-matrix.sh` against the smoke stack**
(`KEEP_TEST_FIXTURES=1` render + smoke Mailpit + `API_BASE`/`KC_BASE`/
`CURL_OPTS`). That is this project's substitute for TurbinePreview's
"matrix over the public URL": production strips the `test-cli` fixtures
by design, so the matrix can never run against the real instance — it
runs against the same compose files locally instead, and the real
instance gets the fixture-free public smoke (verification item 4).

### 6. Go-live runbook (the data no script generates)

Ordered; each step is admin-panel or console work on the real instance,
recorded in Results with counts:

1. **Society inputs** — collected 2026-07-19 (domain, prices, year
   boundaries, renewal-open date, sender mailbox; see Decisions).
   Still to action before their runbook steps: **Stripe account
   access** (step 10; see Decisions item 5 for exactly what) and
   **SMTP AUTH enablement** on `secretary@yasshistory.org.au`
   (step 4).
2. Provision (README §1–2), staging→production ACME, public smoke.
3. Register the first real account through the webapp; grant `admin`
   in the tunnel console (the role model's console-only path).
4. Mail settings page → Exchange preset → test-send; Keycloak realm
   Email config → console "Test connection" + a real forgot-password.
5. Create the membership period and types with the confirmed prices
   (CR-003): Single $45, Household $65, printed *Boongaroon* +$10;
   year 1 Sep – 31 Aug, `renewal_open_date` 1 July,
   `late_joining_cutoff` per clause 5(2)(c).
6. CSV import of the real member list (CR-002 preview → commit);
   reconcile row/household/person counts against the spreadsheet.
7. Hand-enter the POST communication-preference exceptions (the
   handful of print members — roadmap decision 2026-07-18).
8. Committee register: enter the current slate via the CR-013 AGM roll.
9. Email footer (CR-005) with the society's details.
10. Stripe live keys into `.env`, `docker compose up -d`, webhook
    endpoint registered; the real-money verification (§3).
11. Backups: timer enabled, EBS DLM schedule, restore drill
    (verification item 10), `.env` copied once to the society's safe
    keeping.
12. **Self-serve provisioning deferred, deliberately**: run
    `POST /api/admin/self-serve/provision/preview` and record the
    report, but hold the write until the first renewal email has gone
    out and the register has survived contact with reality — CR-006's
    rollout is silent (no invite mail), so provisioning later costs
    nothing and avoids linking accounts against a register that step 6
    might still be correcting.

## Why not alternatives

The load-bearing alternatives (nginx+certbot, ALB/ACM, ECS, RDS,
subdomain Keycloak) were weighed and rejected in TurbinePreview CR-037,
which this deploy directory inherits; nothing in MemberRoll's profile
(~100 members, two small JVMs, one Postgres) reopens them. New to this
CR: **SES instead of Exchange Online** for outbound mail was considered
and rejected — the society already owns a Microsoft 365 tenant whose
domain reputation, SPF and DKIM are established, replies to a real
mailbox matter for a member-facing society, and CR-014 was built
precisely to make a tenant relay a page-configurable target. **A second
env-configured relay for Keycloak** (rendering `smtpServer` from
`.env`) was rejected as churn: the credentials arrive at go-live time
via a human anyway, the console path is one form, and the database is
authoritative post-import regardless.

## What this CR does NOT do

- **No new application features** — CR-007 and the CR-011 stages land
  on the running instance later through the normal `push-war.sh` loop.
- **No off-box backup automation** (S3 sync) — on-box dumps + EBS
  snapshots, same deliberate deferral as CR-037; the yearly archive
  copy is a file move, not infrastructure.
- **No monitoring/alerting stack** — `docker compose logs`, CloudWatch
  free tier, and the CR-005 ABORTED banner (which is the mail-failure
  alarm an admin actually sees). Revisit if the service earns it.
- **No HSTS on day one, no IPv6** — HSTS after the TLS setup has
  soaked.
- **No XOAUTH2** — basic-auth SMTP until Microsoft's Dec 2026 line
  approaches (CR-014 follow-up).
- **No multi-society anything** — single-tenant by decision.

## Decisions (recorded 2026-07-19)

1. **Domain**: `members.yasshistory.org.au`.
2. **Prices unchanged** — Single $45, Household $65, printed
   *Boongaroon* +$10; **year confirmed 1 Sep – 31 Aug**;
   **renewal-open date 1 July**. (Settles the roadmap open question;
   the "1 July rule" for late joiners remains clause 5(2)(c)
   discretion, configured via `late_joining_cutoff`.)
3. **Sender mailbox**: `secretary@yasshistory.org.au` — SMTP AUTH
   enablement on it still to be actioned in the tenant before runbook
   step 4.
4. **Self-serve provisioning**: after the first renewal cycle settles
   (runbook step 12 as proposed).
5. **Stripe — still pending.** What is actually needed, concretely:
   - a Stripe account whose **legal entity and payout bank account are
     the society's** (an account under an individual's name would take
     member payments into that person's name — the account must be
     activated for live mode with the society's details, which Stripe
     verifies: legal/business name, ABN or equivalent, the society's
     bank account for payouts, and a responsible person's identity);
   - **operator access to that account** — a team-member login (Admin
     or Developer role) for whoever runs runbook step 10, sufficient
     to create the restricted live API key and register the webhook
     endpoint + copy its signing secret;
   - a contact email on the account that the committee actually reads
     (payout/dispute notices) — `secretary@yasshistory.org.au` is the
     natural choice.
   If no society account exists yet, creating one is a committee task
   (bank details + identity verification) and is the long pole —
   worth starting before provisioning begins. The fee pass-on
   question stays open and non-blocking (price configuration, step 5).

## Verification plan

1. **Drift closure under the dev stack**: full `verify-matrix.sh`
   against the normal dev stack after the §1 changes — byte-identical
   invocation, expected green baseline (the pre-existing environmental
   flakes noted as such). Proves the matrix refactor changed nothing.
2. **Local smoke, current war** (§5): deploy README §6 verbatim with
   the new assets — Flyway V1–V7 on a fresh prod-topology database,
   health `db: ok`, single smoke issuer, `/auth/admin/` 403, token via
   `test-cli` → `whoami` roles (alias + truststore path), master-realm
   `frontendUrl` pin. Then the **full matrix against the smoke stack**
   via `API_BASE`/`KC_BASE`/`CURL_OPTS` + smoke Mailpit — record the
   counts next to the dev baseline. Confirm the rendered realm (without
   `KEEP_TEST_FIXTURES`) has no `smtpServer`, no `test-cli*` clients,
   no test users; confirm `backup.sh` in the smoke produces **two**
   dumps and no store tarball, and that the `memberroll` dump restores
   into a scratch Postgres with a row-count spot-check.
3. **Provision-from-document** on the real instance: README §1–2
   executed verbatim; every deviation becomes a doc fix in this CR.
   ACME staging first, then production; cert survives
   `docker compose restart caddy` without re-issuance.
4. **Public smoke** (fixture-free, guest-only): `/server/api/health`
   200 `db: ok`; discovery issuer exactly
   `https://<domain>/auth/realms/memberroll`; `/` → `/server/web/`;
   `/auth/admin/` and master realm 403; `whoami` 401 guest;
   `pay/lost-link` 202 regardless of input (the no-enumeration
   contract, now on the public internet); `stripe/webhook` with a
   garbage signature → 400 once keys are live (503 before — both
   observed, in order).
5. **Admin walkthrough over the real domain** (phone on mobile data,
   off the LAN): login via hosted Keycloak, register/renewals/payments
   pages, a receipt print+email, new-member wizard, committee page —
   `window.isSecureContext === true` recorded.
6. **Mail end-to-end**: Mail settings test-send through Exchange
   arrives; a segment send to a one-membership test segment arrives
   with merge fields resolved and `{{payLink}}` pointing at
   `https://<domain>/server/web/pay.html?...`;
   `Authentication-Results` on the received copy shows SPF and DKIM
   pass; Keycloak forgot-password mail arrives and completes a first
   login (the CR-006 path).
7. **Live Stripe end-to-end** (§3): magic link → pay view (server-side
   line items) → Checkout with a real card → webhook records the
   payment → membership PAID → receipt email; then the dashboard
   refund + negative STRIPE payment recorded; a redelivered webhook
   event no-ops on the `external_transaction_id` index.
8. **Real-data reconciliation** (runbook step 6): import counts vs the
   society's spreadsheet — households, people, memberships by type —
   recorded as numbers in Results.
9. **Restart resilience**: instance `reboot` → stack self-heals; the
   imported register, the saved Mail settings row, and a Keycloak
   login all survive (prod-Keycloak-on-Postgres semantics).
10. **Backup/restore drill on the instance**: timer fires; both dumps
    restore into scratch containers; a register query and a Keycloak
    login work against the restore; EBS DLM schedule exists.
11. **Dev-loop regression**: the 18xxx dev stack, dev matrix
    invocation and dev realm are untouched — full matrix green
    locally after all of the above.

## Results

*(to be recorded during implementation)*
