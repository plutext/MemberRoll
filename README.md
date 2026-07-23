# MemberRoll

Membership management and renewals for a small society — built for the
Yass & District Historical Society (~150 members), and designed to be
adapted by any club or association that wants to own its member data
instead of renting it.

MemberRoll is self-hosted and open source (Apache 2.0). There are no
per-member fees, no percentage skimmed off each renewal, and no vendor
roadmap to wait on: online payments cost only Stripe's standard bank fee,
and the running cost is little more than hosting.

## Key features

- **Membership register** — people and households, membership types with
  their own prices (including $0 life membership), full history (members
  are never deleted, changes are dated).
- **Renewals** — annual membership periods, one-click rollover into the
  new year, and a live financial-status view of who has paid, who hasn't,
  and who has lapsed.
- **Online payment without passwords** — renewal emails carry a personal
  pay link; the member clicks, sees their renewal, and pays by card
  (Stripe). No account or login required, and lost links are re-sent
  safely.
- **Manual payments too** — cash, cheque and bank-transfer payments are
  recorded at the desk; corrections are reversals, never edits, so the
  books always reconcile.
- **Email to a segment** — templates with merge fields (including a fresh
  pay link per recipient), a full send log, per-member communication
  preferences, and safe resume if a send is interrupted.
- **Member self-serve** — members can log in to see their household's
  membership status, pay online, and download their membership card.
- **Public application form** — prospective members apply on the website
  (no payment taken, no account needed); an email round trip keeps spam
  out, the committee's decision is recorded with its meeting date, and
  the approval notice carries a pay link for the constitution's 28-day
  window. Ships switched off until the committee approves the form.
- **Membership cards** — a printable/emailable card generated on demand
  from the current register, from the member page or the admin panel,
  with assignable member numbers (so life members can hold the low ones,
  or legacy paper numbers can carry over).
- **Receipts on demand** — any payment can be printed or emailed as a
  receipt, numbered and reproducible.
- **Committee register** — office-bearers and ordinary committee members
  recorded AGM to AGM, with multi-term history.
- **Reports** — the statutory register of members (with join and cease
  dates), who fell through the cracks at rollover, unrenewed households
  for the ring-around, and a date-ranged donations listing — all as CSV.
- **Xero-ready bookkeeping** — a reconciliation export that splits every
  payment into membership / donation / other, plus an importable Xero
  journal that balances against a Stripe clearing account.
- **Mail settings in the admin panel** — the SMTP relay (with a one-click
  Microsoft 365 preset) is configured from the browser and takes effect
  immediately, with a "send test email" button — and a testing sandbox
  that redirects every outgoing email to one address, so a demo against
  real member data can never mail an actual member.
- **Rules that match the constitution** — member-only voting rights,
  September–August membership year, AGM-bounded committee terms: the
  software follows the governing document, not a generic club template.

## Learn more

- **Why build our own?** The committee briefing compares MemberRoll with
  the commercial and open-source alternatives, costed for a 150-member
  society: [docs/membership-software-briefing.md](docs/membership-software-briefing.md)
- **How do I use it?** The user manual covers the admin panel: the
  register, imports, renewals, payments, email and more:
  [docs/user-manual.md](docs/user-manual.md)
- **Can my organisation use it?** Yes — by forking. The intended
  adoption path is AI-assisted customisation to *your* constitution, not
  configuration options: [docs/fork-philosophy.md](docs/fork-philosophy.md)
  (worked example: [docs/forks/](docs/forks/))

## Technical

A Tomcat/Jersey/Keycloak webapp (from webapp-template).

- Roadmap and scope decisions: [docs/ROADMAP.md](docs/ROADMAP.md)
- Getting started (dev loop, LAN phones, production): [docs/GETTING-STARTED.md](docs/GETTING-STARTED.md)
- Server details and Keycloak primer: [server/README.md](server/README.md)
- Deployment: [server/deploy/README.md](server/deploy/README.md)
- Workflow: [docs/change-requests/](docs/change-requests/)

Dev loop:

```bash
mvn clean package
(cd server && docker compose up -d)   # Keycloak :18081 + Postgres :5433 + Mailpit :18025 (UI) / :18026 (SMTP)
mvn -pl server cargo:run              # Tomcat :18080 → http://localhost:18080/server/web/
```

For the CR-004 pieces (pay links, webhook, receipt/lost-link mail) start
cargo with the dev config instead:

```bash
STRIPE_WEBHOOK_SECRET=whsec_devmatrix \
SMTP_HOST=localhost SMTP_PORT=18026 MAIL_FROM=noreply@memberroll.dev \
    mvn -pl server cargo:run
# add STRIPE_SECRET_KEY=sk_test_… (and `stripe listen`) for real Checkout sessions;
# without it the app still runs — the checkout endpoint answers 503, all else works
```

Mail configuration, the dev fresh-start procedure, and the rest of the
server detail live in [server/README.md](server/README.md).

## License

Copyright 2026 Jason Harrop. Licensed under the
[Apache License, Version 2.0](LICENSE).
