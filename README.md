# MemberRoll

A Tomcat/Jersey/Keycloak webapp (from webapp-template).

- User manual (admin panel: register, import, renewals): [docs/user-manual.md](docs/user-manual.md)
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

### Mail configuration

Outbound mail (payment receipts, lost-link replies, segment sends) is
configured **from the admin panel first** — the *Mail settings* page
(admin → Mail settings) saves the SMTP relay (host/port/security/credentials/
from) and takes effect on the very next message, with a "Send test email"
button that shows the relay's own error verbatim. There is a one-click
Microsoft 365 / Exchange Online preset (`smtp.office365.com:587`, STARTTLS).

The `SMTP_*` / `MAIL_FROM` / `MAIL_REPLY_TO` environment variables above are the
**fallback**: used when nothing is saved on that page (so the dev stack and a
fresh install can send before anyone opens it, and an operator who prefers the
password out of the database can stay on env). Resolution order is
page → environment → disabled; with mail disabled, every send is a logged
no-op and the send-dependent endpoints answer 503. Keycloak's own
forgot-password/verification mail is configured separately, in the realm.

Fresh start (wipe the dev database and Keycloak state back to a clean slate):

```bash
# 1. stop the running app FIRST — see the note below on why order matters
#    (Ctrl-C the cargo:run, or: pkill -f cargo:run)
(cd server && docker compose down)    # discard Postgres + Keycloak volumes
(cd server && docker compose up -d)   # fresh Postgres :5433 + Keycloak :18081 (realm re-imported)
mvn -pl server cargo:run              # start the app AGAINST the new DB → Flyway re-creates the schema
```

This leaves you with the V1 schema + V2 seed (the 2025-2026 period, 1 Sep 2025
– 31 Aug 2026, at Single $45 / Household $65) and no members — re-import your
list through the admin Import UI from there.

**Order matters.** `Db` runs Flyway once, at webapp startup. If you recreate
the database while `cargo:run` is still running, the app keeps pointing at a
fresh *empty* Postgres and never re-migrates it — every request then fails
with `relation "…" does not exist`. Always restart cargo *after* a
`compose down && up`.

## License

Copyright 2026 Jason Harrop. Licensed under the
[Apache License, Version 2.0](LICENSE).
