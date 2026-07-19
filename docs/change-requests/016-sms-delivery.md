# CR 016: SMS delivery — an alternative channel to email

Status: PROPOSED (2026-07-20)

## Problem

Every member-facing message today goes by email (CR-004 receipts +
lost-link, CR-005 segment sends, CR-012 receipts). Email is the right
default, but for the one message that matters most — the **renewal
nudge** — a text has a far higher open rate than an email that sits
unread, and members who gave us a mobile but rarely check email are
currently unreachable through the channel they actually watch.

The register already anticipates this and two seams sit unused:

- `communication_preference.delivery_method` (V1) has allowed
  `'EMAIL' | 'POST' | 'SMS' | 'NONE'` since CR-001, and
  `CommunicationPreferenceStore.DELIVERY_METHODS` already lists `SMS`.
  Nothing has ever *actioned* an SMS preference — a person set to SMS is
  simply skipped by the email path today.
- `phone_number` (V1) stores numbers with `phone_type IN ('MOBILE',
  'HOME', 'WORK')`, person-scoped and indexed, but nothing reads it to
  send anything.

This CR is the **first actioner of SMS** — the `phone_number`/`SMS`
analogue of what CR-015 was to `reconciliation_status`: a column the
schema provisioned and left dormant, now given a writer/reader. No
migration is earned.

The research behind provider choice and the Australian compliance
constraints is `docs/sms-messaging-options.md`; the load-bearing
conclusions are folded in below.

## Approach

The shape deliberately mirrors **CR-014 (SMTP settings)** — an `Sms`
utility with per-use `resolve()`, one `app_setting` JSON blob, an
admin-only settings resource with a test-send diagnostic, secret never
echoed/logged. Where CR-014 wrapped jakarta.mail, this wraps a provider
HTTP call. Copy that CR's decisions rather than re-litigating them.

### Storage — one `app_setting` row, no migration

`app_setting` (V5), the generic singleton-config table, gains one key
`sms_settings`, a JSON object:

```json
{"provider": "CLICKSEND", "apiKey": "…", "apiUsername": "…",
 "sender": "+61480000000", "baseUrl": null}
```

- **One row, atomic, no migration** — identical reasoning to CR-014's
  `smtp_settings`: settings change as a unit, a singleton config blob
  doesn't earn a table.
- `provider` is a **closed enum** — `CLICKSEND | MOBILE_MESSAGE | LOG`
  (see provider abstraction). `apiUsername` is provider-shaped (ClickSend
  uses username+api-key; Mobile Message a single key) — optional,
  validated per provider. `sender` is the dedicated virtual number in
  E.164; `baseUrl` overrides the provider default (for a sandbox host,
  and so the matrix can point at a stub) — null = the built-in default.

### Provider abstraction — a closed set, plain JDK, no SDK

An `SmsProvider` interface with one method — `send(Settings, toE164,
text) → Result(ok, providerId?, error?)` — and a small
implementation per enum value, each a single `java.net.http.HttpClient`
POST with the provider's auth header and JSON body. **No vendor SDK**:
the AU gateways are a plain authenticated POST, and a war with no
framework beyond Jersey (CLAUDE.md) should not take a Twilio-sized
dependency to send a one-line text. Adding a provider later is a new
enum arm + a ~30-line class, nothing structural.

`LOG` is the **test/dev provider** — the Mailpit analogue. It does not
hit the network; it records the outbound message (to E.164 + text +
a synthetic id) to an in-memory ring the resource can read back, and
logs it. It is what the verify-matrix and dev cargo run against so the
suite never spends money or depends on a live gateway. (Rejected:
pointing the matrix at a provider sandbox — external, flaky, and some
have no free sandbox; a local stub is the CR-005/Mailpit discipline.)

Recommended first real provider: **ClickSend or Mobile Message** (both
Australian, REST, no monthly fee, dedicated number included/cheap). v1
ships `CLICKSEND` + `MOBILE_MESSAGE` + `LOG`; the operator picks one on
the page.

### Resolution — page settings, then env, then disabled

`Sms.resolve()` returns a `Settings` record + a `source` of
`PAGE | ENV | NONE`, precedence **PAGE → ENV → NONE**, read **per use,
uncached** — CR-014's contract verbatim, and for the same payoff (a
saved change applies to the next message; a DB read failure falls back
to ENV, logged once). `Sms.enabled()` = `resolve().source != NONE`.

There is no legacy SMS env to stay byte-compatible with (this channel
is greenfield), but the ENV arm is still worth having for parity:
`SMS_PROVIDER`/`SMS_API_KEY`/`SMS_API_USERNAME`/`SMS_SENDER`/
`SMS_BASE_URL` as the dev and bootstrap path, and for an operator who
wants the key out of the DB. Dev/matrix set `SMS_PROVIDER=LOG` so the
resting source is a no-cost stub.

### Reaching a person — the `phone_number` + preference join

A new resolver, `SmsStore.mobileFor(handle, personId)` (or a method on
`CommunicationPreferenceStore` — it already owns "how do we reach this
person"): the person's current `MOBILE` `phone_number`, normalised to
E.164 (`+61…`), or null. Import data will hold `04xx xxx xxx` /
`0011…` / spaces — a `normaliseAuMobile()` maps the AU forms to E.164
and **returns null (never a guess) for anything it can't confidently
parse**, so a malformed number is a skip, not a misdirected text.

Channel selection reuses the existing decision function:
`CommunicationPreferenceStore.resolve(...)` already returns EMAIL /
POST / SMS / NONE per person per type. SMS is dispatched only when the
resolved method is `SMS` **and** a normalisable MOBILE exists; an SMS
preference with no usable number is a recorded skip (`NO_NUMBER`),
mirroring CR-005's `NO_EMAIL`. POST/NONE are untouched by this CR.

### First consumer — the renewal / pay-link nudge (transactional)

v1 wires SMS to the highest-value, lowest-risk surface: a **single-
recipient renewal nudge carrying the CR-004 pay link**, triggered from
the admin Renewals view (per membership) — not the CR-005 bulk segment
engine (that stays email in v1; see Out of scope). Composition:

- A short closed template with a tiny merge vocabulary —
  `{{firstName}}`, `{{societyName}}`, `{{payLink}}` — validated
  STRICTLY like `MergeFields` (an unknown token is a 400).
- `{{payLink}}` mints a **fresh** `RenewalTokenStore` token per send
  (the CR-004 amendment CR-005 already follows) and expands to the pay
  URL. **Length note:** a pay URL pushes the message past the 160-char
  GSM-7 single-segment limit, so budget ~2 segments; the composer counts
  segments and surfaces the count in the send response (cost is trivial
  but the number should not be a surprise).
- Sending is best-effort-and-log like `Mail` (a failed text must never
  fail whatever triggered it), on `Sms`' own path — **not** the CR-005
  segment machinery.

### Opt-out — STOP, and the inbound seam

Australian Spam Act practice requires a working opt-out even where the
message is largely transactional. Two halves:

- **Outbound**: every SMS appends " Reply STOP to opt out." within the
  segment budget.
- **Inbound**: a dedicated virtual number gives us a provider inbound
  webhook. `SmsInboundResource` (guest-reachable, provider-signature or
  shared-secret authenticated — the StripeWebhook discipline) receives a
  reply; a body of STOP/UNSUBSCRIBE writes a `communication_preference`
  row of `delivery_method=NONE` for that person (insert-don't-overwrite,
  CR-005), and any other inbound is logged and 200'd. This is the
  functional unsubscribe. (If v1 ships without the inbound webhook, the
  interim is an admin toggling the person's preference to NONE on
  request — but the webhook is cheap given we already have a dedicated
  number, so it is **in** v1 scope.)

### Endpoints — `AdminSmsSettingsResource`, all `@RolesAllowed("admin")`

Mirrors `AdminMailSettingsResource` exactly.

| method | path | behaviour |
|---|---|---|
| GET | `/api/admin/sms-settings` | Effective settings + provenance: `{source, provider, sender, apiUsername, baseUrl, apiKeySet}`. **The apiKey is never returned** — `apiKeySet` is its only trace. |
| PUT | `/api/admin/sms-settings` | Save the row. 400s: `provider` in the enum; `sender` required and E.164-parseable; `apiUsername` required for providers that need it; `apiKey` semantics **absent → keep / "" → clear / else → set** (the CR-014 secret contract). Returns the GET shape. |
| DELETE | `/api/admin/sms-settings` | Remove the row — revert to ENV (or NONE). |
| POST | `/api/admin/sms-settings/test` | Body = candidate settings **+ `{to}`**. Sends one text synchronously with the **candidate** settings and returns `{ok, segments, providerId?, error?}`, the provider's error verbatim — the one place send-failure detail reaches a human. Writes nothing. Against `provider=LOG` it "succeeds" and the message is readable from the LOG ring (how the matrix asserts). |

Plus the first-consumer send endpoint on the existing Renewals surface
(shape TBD in implementation, e.g. `POST /api/admin/memberships/{id}/
sms-reminder`), `@RolesAllowed("admin")`, 503 when `Sms.enabled()` is
false (the checkout-mirror, never a silent no-op), 422 when the member
has no usable mobile.

### UI — `admin/sms-settings.html`

The CR-014 page pattern: provenance banner ("Using settings saved on
this page" / "the server environment" / "SMS is disabled"); a
**provider preset** (ClickSend / Mobile Message) that fills the base URL
and shows the right credential fields — a client-side form-filler, the
server knows no vendors; a test-send field + button showing the result
or the provider's error string; Save (PUT) and Use server defaults
(DELETE, confirm). apiKey input shows "(unchanged)" when `apiKeySet`.
Static guidance: use a **dedicated virtual number, not an alphanumeric
sender** (see Compliance), and the mobile-coverage caveat. The Renewals
view gains a **Text reminder** action beside the existing email one,
disabled with a hint when `Sms.enabled()` is false (the CR-012 banner
pattern) or the member has no mobile.

### Secrets

`apiKey` stored plaintext in `app_setting`, admin-only at the API, never
echoed (only `apiKeySet`), never logged, and **scrubbed from the test
endpoint's error string** — the CR-014 hazard, verbatim: provider error
bodies can echo request state, so assert the returned error contains no
part of the key. Env remains for operators who want the key out of the DB.

## Compliance (Australia) — load-bearing, not optional

From `docs/sms-messaging-options.md`, the two rules that constrain the design:

- **ACMA SMS Sender ID Register** — in force since **1 July 2026**. A
  **branded alphanumeric sender** ("YASSHIST") must be registered via the
  provider or recipients' phones flag the message **"Unverified"** and
  group it as suspected scam. v1 therefore sends from a **dedicated
  virtual mobile number**, which needs no registration *and* gives us the
  inbound STOP webhook. An alphanumeric sender is a documented follow-up
  (it requires the registration step through the provider).
- **Spam Act 2003** — consent + functional opt-out. Consent rides the
  existing `communication_preference` (an SMS preference is opt-in by
  definition; the household/person default is EMAIL, so SMS is never the
  silent default). Opt-out is the STOP handling above.

## Config

No migration, no realm change. New optional env `SMS_PROVIDER` (incl.
`LOG` for dev) `/ SMS_API_KEY / SMS_API_USERNAME / SMS_SENDER /
SMS_BASE_URL` as the fallback/dev path — documented in README /
GETTING-STARTED alongside the CR-014 mail note. Dev compose /
verify-matrix set `SMS_PROVIDER=LOG`.

## Verification plan

New `CR16-*` rows in `server/verify-matrix.sh`, dev stack with
`SMS_PROVIDER=LOG` as the resting source; each mutating row cleans up
so the matrix stays re-runnable (end state: no `sms_settings` row).

| # | caller / call | expect |
|---|---|---|
| 1 | guest / user / noaud → GET/PUT/DELETE `/api/admin/sms-settings`, POST `.../test`, POST `.../sms-reminder` | 403 / 403 / 401 |
| 2 | admin → GET with no row (env `SMS_PROVIDER=LOG`) | `source=ENV`, provider `LOG`, `apiKeySet=false`, no `apiKey` key |
| 3 | admin → PUT {CLICKSEND, sender +61…, apiUsername, apiKey} | 200; GET → `source=PAGE`, fields echoed, `apiKeySet=true`, no `apiKey` key |
| 4 | admin → PUT bad provider / missing sender / non-E.164 sender / CLICKSEND without apiUsername | 400 each; GET unchanged after all |
| 5 | admin → re-PUT with `apiKey` absent → still set; then `apiKey:""` → cleared | 200 each; `apiKeySet` true then false |
| 6 | admin → POST `.../test` {candidate=LOG, to} | `{ok:true, segments}`; message present in the LOG ring with that `to`/text; saved row untouched |
| 7 | admin → POST `.../test` {candidate CLICKSEND, bad baseUrl→closed port, to} | `{ok:false}`, `error` names the connection/HTTP failure; nothing recorded |
| 7b | admin → POST `.../test` {candidate with a wrong apiKey against a stub returning an auth error} | `{ok:false}`, error names the auth failure and **does not contain the apiKey** (secret-leak hazard) |
| 8 | number normalisation: unit-drive `normaliseAuMobile` over `0412 345 678` / `+61412345678` / `61412345678` / `0011…` / `notanumber` / landline `0298…` | E.164 for the mobile forms, **null** for the unparseable and the landline |
| 9 | admin → POST `.../sms-reminder` for a member with a MOBILE + SMS/EMAIL pref | 200, `{segments, providerId}`; LOG ring holds one text to that number containing a fresh pay link and "Reply STOP" |
| 10 | admin → POST `.../sms-reminder` for a member with no MOBILE | 422 (`NO_NUMBER`), nothing recorded |
| 11 | inbound: POST `SmsInboundResource` {from=that mobile, body="STOP"} with a valid secret; then GET the person's preferences | 200; the person now resolves `NONE` for the type (opt-out written) |
| 11b | inbound with a bad/missing secret | 401/403; no preference change |
| 12 | `Sms.enabled()` gate: DELETE the row **and** (manual) run a cargo with no SMS env → GET `source=NONE`, `.../test` and `.../sms-reminder` answer 503; the Renewals Text action disabled | 503 / disabled |
| 13 | psql | exactly one `sms_settings` row after the PUTs (upsert, not append); one `NONE` preference from row 11 — then cleaned up |

Row 12's `NONE` leg is the CR-014-style manual row (env is fixed at
cargo start). Browser walkthrough (Playwright, house recipe): open SMS
settings; pick the ClickSend preset and see fields fill; overwrite with
`provider=LOG` + a sender; test send → success line + segment count;
Save → banner flips to "saved on this page"; reload → apiKey
"(unchanged)"; open Renewals, fire a Text reminder → success with
segment count; Use server defaults → banner back to env.

## Implementation notes (read before building)

Hazards first, then mechanics.

1. **Secret leak through the test error string** — the CR-014 hazard,
   same fix: provider HTTP error bodies can echo the request; assert the
   returned/logged error contains no part of the apiKey.
2. **Never send to a guessed number.** `normaliseAuMobile` returns null
   for anything it can't confidently parse (landlines included — a HOME
   number is not a mobile); the caller treats null as a skip. A text to
   the wrong person is worse than no text.
3. **Best-effort everywhere but the test/first-consumer response.** A
   failed SMS logs and moves on; it must never roll back a payment,
   membership change, or the triggering request — `Mail`'s contract.
4. **Idempotency of the inbound webhook** — a provider may redeliver;
   writing a `NONE` preference must be a no-op when the current method is
   already NONE (the insert-don't-overwrite check CR-005 uses).

Mechanics / house patterns:

- `Sms` is a static utility using `Db.jdbi()` for `resolve()`, no DI, no
  cached `Settings` — per-use resolution is the design (mid-flow pickup),
  as with `Mail`.
- New files: `Sms`, `SmsProvider` (+ `CLICKSEND`/`MOBILE_MESSAGE`/`LOG`
  arms), `AdminSmsSettingsResource`, `SmsInboundResource`, the reminder
  method on the Renewals resource, `admin/sms-settings.html` + `admin.js`
  wiring + the menu entry. Register the new resources in `ApiApplication`
  (explicit, no scanning).
- Reuse `RenewalTokenStore` for `{{payLink}}` (fresh token per send) and
  `CommunicationPreferenceStore.resolve` for channel selection — no new
  copies of either rule.
- `CR16-*` rows self-clean (no `sms_settings` row, no stray preference)
  so the matrix stays re-runnable; `SMS_PROVIDER=LOG` keeps it free and
  offline. Extend the matrix, don't fork it.
- Playwright per the house recipe (NODE_PATH to npx-cached playwright;
  restart cargo after Java changes; `mvn clean` under a running cargo
  serves 404s).
- Record results in this doc (numbers, not adjectives) before close-out.

## Out of scope / follow-ups

- **SMS in the CR-005 bulk segment engine** — the natural next step: let
  a segment send dispatch SMS to SMS-preference recipients. Deferred
  because the `email_send` log is email-shaped (subject/body/footer);
  doing it right means generalising the send log to a channel column (or
  a parallel `sms_send`) and is its own CR. v1's transactional nudge
  proves the channel first.
- **Alphanumeric sender ID** — needs ACMA Sender ID Register
  registration through the provider; the settings JSON grows a
  `senderType` field then. v1's dedicated number needs none.
- **MMS / delivery-receipt ingestion / two-way conversations** beyond
  STOP — not earned at this volume.
- **Mobile-coverage report** — an admin count of ACTIVE members with vs
  without a usable MOBILE, to size how many SMS can actually reach before
  the society commits. Cheap; a good pre-flight but not blocking.

## Close-out (on implementation)

- `CLAUDE.md`: an architecture paragraph (SMS as the first actioner of
  `phone_number`/`communication_preference` SMS; `Sms` mirrors `Mail`'s
  resolve-per-use; dedicated-number/Sender-ID-Register rationale;
  best-effort discipline) and the dev-command note for `SMS_PROVIDER=LOG`.
- `README` / `GETTING-STARTED`: SMS configuration section — the page
  first, env as bootstrap/dev, the LOG provider for local runs.
- `docs/ROADMAP.md`: CR-016 row.
