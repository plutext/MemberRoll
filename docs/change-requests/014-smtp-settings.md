# CR 014: SMTP settings page — configure mail from the admin panel

Status: PROPOSED (2026-07-19)

## Problem

Every outbound mail path (CR-004 receipts + lost-link, CR-005 segment
sends, CR-012 receipt email) reads its SMTP configuration from the
service environment (`SMTP_HOST`/`SMTP_PORT`/`SMTP_USERNAME`/
`SMTP_PASSWORD`/`SMTP_STARTTLS`/`MAIL_FROM`/`MAIL_REPLY_TO`). That was
the right CR-004 minimum, but it means changing the relay — or fixing a
rotated password — is an SSH-edit-the-unit-file-and-restart operation.
The society's operators are not going to do that.

The society will send through **Exchange Online**
(`smtp.office365.com`). The concrete need: an admin page where SMTP can
be configured — one that "just works" for Microsoft 365 but is a
general SMTP form (host/port/security/credentials), because the relay
may change (a different tenant, a transactional provider, a local
relay).

Microsoft context that shapes the design (verified 2026-07-19):

- Client submission is `smtp.office365.com:587` with STARTTLS and
  username/password (SMTP AUTH basic). **Basic auth is unchanged
  through December 2026**; from the end of December 2026 it becomes
  *default-disabled* for existing tenants (a tenant admin can re-enable
  it), and the final removal date will only be announced in H2 2027.
  So password auth is the correct v1; OAuth (XOAUTH2) is a recorded
  follow-up, not this CR.
- The authenticated mailbox must have "Authenticated SMTP" enabled
  (off by default on newer tenants), and the From address must be that
  mailbox or one it has Send As rights over. These are tenant-side
  facts the page can only *document*, not detect — the test-send button
  is how the admin finds out.

## Approach

### Storage — one `app_setting` row, no migration

`app_setting` (V5) is "generic on purpose"; this CR uses it as
intended. One key, `smtp_settings`, whose value is a JSON object:

```json
{"host": "smtp.office365.com", "port": 587, "security": "STARTTLS",
 "username": "noreply@society.org.au", "password": "…",
 "from": "noreply@society.org.au", "replyTo": "treasurer@society.org.au"}
```

- **One row, not seven** (the `email_footer` per-key pattern): SMTP
  settings change as a unit — a host swap with the old password half-
  applied is a meaningless state. A single JSON value is atomic for
  free; seven rows would need a transaction and partial reads would
  still be representable.
- **Not a dedicated table**: a singleton config blob doesn't earn a
  migration plus store ceremony. If a second consumer ever needs
  structured queries over mail config, promote it then.
- `security` is a closed enum: `NONE` | `STARTTLS` | `SSL` (implicit
  TLS, port 465 convention). This replaces the env path's boolean
  `SMTP_STARTTLS` with the three cases real relays actually present.

### Resolution — page settings, then env, then disabled

`Mail` gains a `Settings` record and a `resolve()` that returns it with
a `source` of `PAGE` (the `app_setting` row), `ENV` (today's variables,
verbatim behaviour), or `NONE` (mail disabled). Precedence is
**PAGE → ENV → NONE**:

- Env stays as the dev default — the dev command line, compose stack
  and the whole existing verify-matrix keep working with an empty
  database, and it remains the bootstrap path (a fresh production
  install can send before anyone opens the page, or never store a
  password in the DB at all if the operator prefers env).
- Settings are read **per use, uncached** — the table is one PK lookup
  and sends are rare. A save applies to the very next message; notably,
  a CR-005 segment send that ABORTED on a dead relay can be fixed from
  the page and resumed without a restart, and the sequential sender
  picks the new settings up between messages.
- If the DB read itself fails, `resolve()` falls back to ENV (logged):
  mail is best-effort everywhere (a receipt must never fail the webhook
  that recorded the payment), and a DB hiccup must not change that.

`Mail.send` maps the record onto jakarta.mail properties as today, with
one hardening delta: `security=STARTTLS` sets
`mail.smtp.starttls.required=true` as well as `.enable` — a configured
production relay must not silently downgrade to cleartext when a MITM
strips the capability. (The ENV path keeps its current
enable-only behaviour: dev Mailpit doesn't offer STARTTLS.)
`Mail.enabled()` becomes `resolve().source != NONE`, so every existing
gate — checkout-mirror 503s, the CR-012 Email button's `mailEnabled`,
the CR-005 banner — reflects the page automatically.

Everything reaches the row through the shared `Db.jdbi()` the same as
the stores; no new plumbing.

### Endpoints — `AdminMailSettingsResource`, all `@RolesAllowed("admin")`

| method | path | behaviour |
|---|---|---|
| GET | `/api/admin/mail-settings` | The *effective* settings + provenance: `{source: PAGE\|ENV\|NONE, host, port, security, username, from, replyTo, passwordSet}`. **The password is never returned** — `passwordSet` is the only trace of it. |
| PUT | `/api/admin/mail-settings` | Save the page settings (writes the `smtp_settings` row). Validation, all 400s: `host` required; `port` in 1–65535; `security` in the enum; `from` required and a parseable address; `replyTo` optional but parseable; `username` optional (unauthenticated relays exist); `password` only meaningful alongside `username`. Password field semantics: **absent → keep the stored one; empty string → clear it; otherwise → set it** — so re-saving the form never requires retyping the secret. Returns the GET shape. |
| DELETE | `/api/admin/mail-settings` | Remove the row — revert to ENV (or NONE where no env is set). |
| POST | `/api/admin/mail-settings/test` | Body = candidate settings **+ `{to}`**. Sends one test message **synchronously with the candidate settings from the body, not the saved ones** — the test-before-save flow — and returns `{ok, error?}` with the SMTP exception message verbatim. This is deliberately the one place send-failure detail reaches a human (everywhere else is best-effort-and-log): "535 5.7.139 Authentication unsuccessful" *is* the product for an admin debugging a tenant setting. Candidate password absent → the stored one is used, so a test after reopening the page needs no retype. Writes nothing; the existing 10s connect/read/write timeouts bound the wait. |

### The Microsoft preset — UI, not server

`admin/mail-settings.html` (new page, menu entry beside the others):

- **Provenance banner** — "Using settings saved on this page" / "Using
  the server environment" / "Mail is disabled", from GET's `source`
  (the CR-005 banner pattern).
- **Preset: Microsoft 365 / Exchange Online** — a button that fills
  host `smtp.office365.com`, port `587`, security `STARTTLS`, and
  mirrors the From address into username. A preset is a form-filler,
  nothing more — the server knows no vendors.
- Static guidance under the preset (the tenant-side facts the app
  cannot check): username must be the full mailbox address; From must
  be that mailbox (or Send As); the mailbox needs "Authenticated SMTP"
  enabled in the Microsoft admin center; and the basic-auth horizon
  (default-off end of 2026, re-enableable; see Follow-ups).
- **Send test email** — a `to` field + button over `POST .../test`,
  result (or the relay's error string) shown inline.
- **Save** (PUT) and **Use server defaults** (DELETE, confirm dialog).
  The password input shows placeholder "(unchanged)" when `passwordSet`
  — the absent-means-keep contract.
- One-line note that Keycloak's forgot-password mail is configured in
  the realm, separately (see below).

### Secrets

The password is stored plaintext in `app_setting`, admin-only at the
API, never echoed to any client, never logged. Rejected: encrypting it
at the application layer — on this topology (single host, DB and
Tomcat side by side, key would live in the same environment the
password lives in today) it changes no threat model, only adds a
key-management failure mode. Env remains available for operators who
want the secret out of the DB entirely.

### Out of scope

- **Keycloak's own SMTP** (forgot-password/verify mail): realm
  configuration, a different sender with its own lifecycle. The page
  notes the distinction; changing it stays a realm/console operation
  (mirrored into `server/keycloak/` per the dev/prod discipline).
- **OAuth2/XOAUTH2 for Microsoft** — not needed before 2027 at the
  earliest; recorded as the follow-up (the settings JSON grows an
  `authType` field then).
- **Other settings** (society name, public base URL): a later general
  settings page could subsume this one; not this CR.

## Config

No new env, no migration, no realm change. The existing `SMTP_*` /
`MAIL_FROM` / `MAIL_REPLY_TO` variables are demoted from "the
configuration" to "the fallback" — README/GETTING-STARTED updated to
say so.

## Verification plan

New `CR14-*` rows in `server/verify-matrix.sh` (dev stack: env points
at Mailpit as today, so ENV is the resting source between rows; each
mutating row cleans up after itself so the matrix stays re-runnable).

| # | caller / call | expect |
|---|---|---|
| 1 | guest / user / noaud → GET, PUT, DELETE `/api/admin/mail-settings`, POST `.../test` | 403 / 403 / 401 |
| 2 | admin → GET with no row | `source=ENV`, host mirrors the env value, `passwordSet=false`, no `password` key in the JSON |
| 3 | admin → PUT {Mailpit host/port, NONE, from=page@…, username+password} | 200; GET → `source=PAGE`, fields echoed, `passwordSet=true`, no `password` key |
| 4 | admin → PUT missing host / port 0 / port 70000 / security BOGUS / unparseable from | 400 each; GET unchanged after all five |
| 5 | admin → re-PUT with the `password` field absent | 200; `passwordSet` still true (kept). Then PUT `password:""` → `passwordSet=false` (cleared) |
| 6 | admin → POST `.../test` {candidate=Mailpit, to} | `{ok:true}`; the message arrives in Mailpit (API check) with the candidate From; saved row untouched |
| 7 | admin → POST `.../test` {candidate with a closed port} | `{ok:false}`, `error` names the connection failure; nothing sent, nothing saved |
| 8 | precedence: PUT settings at a dead port, then trigger a CR-012 receipt email | POST accepted (async best-effort) but nothing new arrives in Mailpit — PAGE beat ENV |
| 9 | admin → DELETE, re-trigger the receipt email | 200, GET → `source=ENV`; the mail arrives again — fallback restored |
| 10 | end-to-end via PAGE: PUT Mailpit settings with a distinctive From, trigger a receipt email | mail arrives with the page-configured From — the real send path reads the row, not env |
| 11 | psql | exactly one `smtp_settings` row after the run's PUTs (upsert, not append) — then cleaned up |

`source=NONE` (no row, no env → every mail surface 503s/disables)
can't be shown inside the matrix run (env is fixed at cargo start); it
is covered by a manual row: start cargo without the SMTP env, GET shows
`NONE`, the CR-012 Email button disables, `POST .../receipt` answers
503 — the existing gates, now driven by `resolve()`.

Browser walkthrough (Playwright): open Mail settings; click the
Microsoft 365 preset and see host/port/security fill; overwrite with
Mailpit values; test send → success line, message visible in Mailpit
UI; Save → banner flips to "settings saved on this page"; reload →
password placeholder "(unchanged)"; Use server defaults → banner back
to "server environment".

## Close-out (on implementation)

- `CLAUDE.md`: architecture paragraph (resolution order, the one-row
  JSON decision, test-send as the diagnostic surface) and update the
  dev-command note that env is now the fallback.
- `README` / `GETTING-STARTED`: mail configuration section — the page
  first, env as bootstrap/dev.
- `docs/ROADMAP.md`: CR-014 row.

## Follow-ups / amendments

- **XOAUTH2 for Exchange Online** when Microsoft removes basic auth
  (default-off end of Dec 2026, tenant-re-enableable; removal date to
  be announced H2 2027): client-credentials token via MSAL, jakarta.mail
  `XOAUTH2` mechanism, an `authType` field in the settings JSON and a
  tenant/client-id/secret triple on the page. Nothing in this CR's
  shape blocks it.
- A general settings page (society name, public base URL) if more
  configuration moves from env to DB.
