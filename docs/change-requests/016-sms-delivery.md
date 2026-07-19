# CR 016: SMS delivery — an alternative channel to email

Status: PROPOSED (2026-07-20; amended same day on review — number-keyed
`sms_opt_out` register (V8) replaces the preference-NONE STOP write, the
channel-gating/consent contradiction resolved, `inboundSecret` added to
the settings blob, LOG ring read endpoint specified, 422 → 409)

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
schema provisioned and left dormant, now given a writer/reader. Those
seams need no migration; the one concept the schema never provisioned —
a number-keyed SMS opt-out register (see Opt-out) — earns V8.

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
 "sender": "+61480000000", "inboundSecret": "…", "baseUrl": null}
```

- **One row, atomic, no migration** — identical reasoning to CR-014's
  `smtp_settings`: settings change as a unit, a singleton config blob
  doesn't earn a table. (The V8 migration below is the opt-out
  *register*, not settings.)
- `provider` is a **closed enum** — `CLICKSEND | MOBILE_MESSAGE | LOG`
  (see provider abstraction). `apiUsername` is provider-shaped (ClickSend
  uses username+api-key; Mobile Message a single key) — optional,
  validated per provider. `sender` is the dedicated virtual number in
  E.164; `baseUrl` overrides the provider default (for a sandbox host,
  and so the matrix can point at a stub) — null = the built-in default.
- `inboundSecret` (added on review — the first draft gave the inbound
  webhook's auth nowhere to live) is the shared secret
  `SmsInboundResource` requires (see Opt-out). It is a second secret and
  follows the apiKey contract exactly: absent → keep / "" → clear, never
  echoed (only `inboundSecretSet`), never logged, scrubbed from errors.

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
the page. Be honest about verification: only the LOG arm is
matrix-verifiable — the two live arms (~30 lines each) are proven by a
recorded real test-send at go-live with whichever provider the society
opens an account with, plus row 7's wrong-key/closed-port negative
paths against a stub.

### Resolution — page settings, then env, then disabled

`Sms.resolve()` returns a `Settings` record + a `source` of
`PAGE | ENV | NONE`, precedence **PAGE → ENV → NONE**, read **per use,
uncached** — CR-014's contract verbatim, and for the same payoff (a
saved change applies to the next message; a DB read failure falls back
to ENV, logged once). `Sms.enabled()` = `resolve().source != NONE`.

There is no legacy SMS env to stay byte-compatible with (this channel
is greenfield), but the ENV arm is still worth having for parity:
`SMS_PROVIDER`/`SMS_API_KEY`/`SMS_API_USERNAME`/`SMS_SENDER`/
`SMS_INBOUND_SECRET`/`SMS_BASE_URL` as the dev and bootstrap path, and
for an operator who wants the secrets out of the DB. Dev/matrix set `SMS_PROVIDER=LOG` so the
resting source is a no-cost stub.

### Reaching a person — the `phone_number` + preference join

A new resolver, `SmsStore.mobileFor(handle, personId)` (or a method on
`CommunicationPreferenceStore` — it already owns "how do we reach this
person"): the person's current `MOBILE` `phone_number`, normalised to
E.164 (`+61…`), or null. Import data will hold `04xx xxx xxx` /
`0011…` / spaces — a `normaliseAuMobile()` maps the AU forms to E.164
and **returns null (never a guess) for anything it can't confidently
parse**, so a malformed number is a skip, not a misdirected text.

Channel gating (rewritten on review — the first draft was internally
contradictory: this section required a resolved method of `SMS` while
the verification plan sent the nudge to an EMAIL-preference member, and
the Compliance section hung its consent argument on the requirement the
plan violated). The rule, two-tier:

- **Anything unattended** (the CR-005 bulk engine when a later CR adds
  SMS to it, or any future automatic dispatch) sends only when
  `CommunicationPreferenceStore.resolve(...)` returns `SMS` — the
  opt-in reading stands wherever no human chose the recipient.
- **v1's admin-triggered nudge** is a deliberate human act on one
  membership, transactional in content (that member's own renewal + pay
  link), so it MAY go to a member whose resolved RENEWAL method is
  EMAIL or POST (Spam Act: inferred consent from the existing
  membership relationship) — but NEVER when the resolved method is
  `NONE` ("asked for nothing" gates every channel; 409
  `PREFERENCE_NONE`) and NEVER to a number in the opt-out register
  (409 `OPTED_OUT` — enforced at the `Sms.send` chokepoint, see
  Opt-out).
- No usable MOBILE (missing, or unnormalisable) is a 409 (`NO_NUMBER`)
  on the admin endpoint and would be a recorded skip in a future bulk
  path, mirroring CR-005's `NO_EMAIL`. POST is untouched by this CR.

### First consumer — the renewal / pay-link nudge (transactional)

v1 wires SMS to the highest-value, lowest-risk surface: a **single-
recipient renewal nudge carrying the CR-004 pay link**, triggered from
the admin Renewals view (per membership) — not the CR-005 bulk segment
engine (that stays email in v1; see Out of scope).

**Whose mobile** (specified on review — a membership can cover two
MEMBER people, and the first draft never said): the CR-005 attribution
order — the household's current MEMBER-relationship people, primary
contact first, else ascending person id — taking the FIRST with a
usable number (normalisable MOBILE, not opted out). One membership, one
text: a couple with two phones texts the attributed person only,
exactly as a couple sharing an email gets one CR-005 message.

Composition:

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

### Opt-out — STOP, a number-keyed register (V8), and the inbound seam

Australian Spam Act practice requires a working opt-out even where the
message is largely transactional. The first draft wrote a
`communication_preference` row of `NONE` "for that person" on STOP;
review killed that on three counts: `NONE` over-reaches (the per-type
single-method model cannot say "no SMS, email fine" — it would silence
the member's renewal *email* too, which neither party wants); the draft
never said which of the four communication types to write (a STOP means
"stop texting me", not "stop renewal comms"); and mapping an inbound
number to ONE person is ill-defined (couples share a mobile; stored
numbers are free text). The opt-out is therefore keyed on what the Spam
Act actually attaches to — the **number**:

- **V8** adds `sms_opt_out` (`number` text E.164 PK, `opted_out_at`
  timestamptz, `source` text — `INBOUND_STOP | ADMIN`). A tiny
  insert-if-absent register with deliberately **no FK to person**: a
  number opts out whoever holds it now or later, matching the legal
  semantics and sidestepping person-matching entirely.
- **Outbound**: every SMS appends " Reply STOP to opt out." within the
  segment budget, and **`Sms.send` is the single enforcement point** —
  it normalises the destination and refuses any number present in
  `sms_opt_out`. Every caller inherits the gate (the admin endpoints
  surface it as 409 `OPTED_OUT`; the test endpoint refuses too — one
  chokepoint, no bypass path).
- **Inbound**: the dedicated virtual number gives us a provider inbound
  webhook. `SmsInboundResource` (`POST /api/sms/inbound`,
  guest-reachable; auth = the settings blob's `inboundSecret` compared
  constant-time, carried as a query parameter — the AU gateways do not
  HMAC-sign payloads the way Stripe does, so a shared secret is the
  honest mechanism; no secret configured anywhere → 503, the checkout
  mirror) receives a reply. A body of STOP/UNSUBSCRIBE (case-
  insensitive, trimmed) normalises `from` and inserts into
  `sms_opt_out` if absent — so a provider redelivery is a natural no-op
  (the PK is the idempotency guard, the StripeWebhook discipline). Any
  other inbound, and a `from` that doesn't normalise, is logged and
  200'd. `communication_preference` is NOT touched: email and post
  continue, and if the member wants those stopped too, that is the
  existing preference UI.
- Un-opt-out is deliberately admin-only in v1 (delete the row on the
  member's request); automatic START handling is a follow-up on the
  same inbound seam.

### Endpoints — `AdminSmsSettingsResource`, all `@RolesAllowed("admin")`

Mirrors `AdminMailSettingsResource` exactly.

| method | path | behaviour |
|---|---|---|
| GET | `/api/admin/sms-settings` | Effective settings + provenance: `{source, provider, sender, apiUsername, baseUrl, apiKeySet, inboundSecretSet}`. **Neither secret is ever returned** — the `…Set` booleans are their only trace. |
| PUT | `/api/admin/sms-settings` | Save the row. 400s: `provider` in the enum; `sender` required and E.164-parseable; `apiUsername` required for providers that need it; `apiKey` AND `inboundSecret` semantics **absent → keep / "" → clear / else → set** (the CR-014 secret contract). Returns the GET shape. |
| DELETE | `/api/admin/sms-settings` | Remove the row — revert to ENV (or NONE). |
| POST | `/api/admin/sms-settings/test` | Body = candidate settings **+ `{to}`**. Sends one text synchronously with the **candidate** settings and returns `{ok, segments, providerId?, error?}`, the provider's error verbatim — the one place send-failure detail reaches a human. Writes nothing. Goes through the `Sms.send` chokepoint, so an opted-out `to` is refused even here. Against `provider=LOG` it "succeeds" into the LOG ring. |
| GET | `/api/admin/sms-settings/log` | The LOG provider's in-memory ring, newest first: `{messages: [{to, text, at}]}` — how the matrix asserts what was "sent" (specified on review; the first draft asserted against the ring but gave it no read path). Per-JVM and empty after a restart; empty unless the effective provider is LOG. Harmless in production (admin-only, and a real provider never populates it). |

Plus the first-consumer send endpoint on the existing Renewals surface:
`POST /api/admin/memberships/{id}/sms-reminder` on
`AdminMembershipsResource`, beside the CR-004 pay-link mint,
`@RolesAllowed("admin")`. 503 when `Sms.enabled()` is false (the
checkout-mirror, never a silent no-op); 409 — not the first draft's
422, a status this codebase's vocabulary doesn't use and the matrix
never asserts — with a `reason` of `NO_NUMBER` / `OPTED_OUT` /
`PREFERENCE_NONE` (see Channel gating). And `POST /api/sms/inbound`
(`SmsInboundResource`, guest-reachable, secret-gated — see Opt-out).

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
view gains a **Text reminder** action beside the existing **Pay link**
mint (corrected on review: there is no per-membership *email* action
today — the pay-link URL is what the treasurer pastes into a manual
email, so this is the first one-click send from that view), disabled
with a hint when `Sms.enabled()` is false (the CR-012 banner pattern)
or the member has no usable mobile.

### Secrets

`apiKey` and `inboundSecret` stored plaintext in `app_setting`,
admin-only at the API, never echoed (only the `…Set` booleans), never
logged, and **scrubbed from the test endpoint's error string** — the
CR-014 hazard, verbatim: provider error bodies can echo request state,
so assert the returned error contains no part of either secret. Env
remains for operators who want the secrets out of the DB.

## Compliance (Australia) — load-bearing, not optional

From `docs/sms-messaging-options.md`, the two rules that constrain the design:

- **ACMA SMS Sender ID Register** — in force since **1 July 2026**. A
  **branded alphanumeric sender** ("YASSHIST") must be registered via the
  provider or recipients' phones flag the message **"Unverified"** and
  group it as suspected scam. v1 therefore sends from a **dedicated
  virtual mobile number**, which needs no registration *and* gives us the
  inbound STOP webhook. An alphanumeric sender is a documented follow-up
  (it requires the registration step through the provider).
- **Spam Act 2003** — consent + functional opt-out. Consent is
  two-tier, matching Channel gating: anything *automatic* requires an
  explicit SMS preference (opt-in by definition — the default is EMAIL,
  so SMS is never the silent default), while the v1 admin-triggered
  nudge rests on inferred consent from the existing membership
  relationship, which the Act recognises for exactly this kind of
  transactional message. The functional opt-out is the number-keyed
  STOP register above, enforced at the `Sms.send` chokepoint — no code
  path can text an opted-out number, and the matrix proves it (row 11c).

## Config

One migration (V8, the `sms_opt_out` register — see Opt-out), no realm
change. New optional env `SMS_PROVIDER` (incl. `LOG` for dev)
`/ SMS_API_KEY / SMS_API_USERNAME / SMS_SENDER / SMS_INBOUND_SECRET /
SMS_BASE_URL` as the fallback/dev path — documented in README /
GETTING-STARTED alongside the CR-014 mail note. Dev compose /
verify-matrix set `SMS_PROVIDER=LOG`.

## Verification plan

New `CR16-*` rows in `server/verify-matrix.sh`, dev stack with
`SMS_PROVIDER=LOG` as the resting source; each mutating row cleans up
so the matrix stays re-runnable (end state: no `sms_settings` row,
empty `sms_opt_out`).

| # | caller / call | expect |
|---|---|---|
| 1 | guest / user / noaud → GET/PUT/DELETE `/api/admin/sms-settings`, POST `.../test`, GET `.../log`, POST `.../sms-reminder` | 403 / 403 / 401 |
| 2 | admin → GET with no row (env `SMS_PROVIDER=LOG`) | `source=ENV`, provider `LOG`, `apiKeySet=false`, `inboundSecretSet=false`, no secret keys in the body |
| 3 | admin → PUT {CLICKSEND, sender +61…, apiUsername, apiKey, inboundSecret} | 200; GET → `source=PAGE`, fields echoed, `apiKeySet=true`, `inboundSecretSet=true`, no secret keys in the body |
| 4 | admin → PUT bad provider / missing sender / non-E.164 sender / CLICKSEND without apiUsername | 400 each; GET unchanged after all |
| 5 | admin → re-PUT with `apiKey`/`inboundSecret` absent → both still set; then both `""` → both cleared | 200 each; the `…Set` booleans true then false |
| 6 | admin → POST `.../test` {candidate=LOG, to} | `{ok:true, segments}`; GET `.../log` shows that `to`/text newest-first; saved row untouched |
| 7 | admin → POST `.../test` {candidate CLICKSEND, bad baseUrl→closed port, to} | `{ok:false}`, `error` names the connection/HTTP failure, returns within the short connect timeout (see note 5); nothing recorded |
| 7b | admin → POST `.../test` {candidate with a wrong apiKey against a stub returning an auth error} | `{ok:false}`, error names the auth failure and **does not contain the apiKey** (secret-leak hazard) |
| 8 | number normalisation, driven through the API (this project has no unit-test suite — the matrix is the vehicle): POST `.../test` {LOG, to} for each of `0412 345 678` / `+61412345678` / `61412345678` / `0011 61 412 345 678` / `notanumber` / landline `02 9812 3456` | the three mobile forms succeed and GET `.../log` shows the SAME normalised `+61412345678` for each; the rest 400 ("to is not a usable AU mobile"), nothing in the ring |
| 9 | admin → POST `.../sms-reminder` for a member with a usable MOBILE whose resolved RENEWAL method is EMAIL (the deliberate-human-act tier), then for one whose preference is SMS | 200 each, `{segments, providerId}`; GET `.../log` holds one text per send, to the attributed person's E.164, containing a fresh pay link and "Reply STOP" |
| 9b | admin → `.../sms-reminder` for a membership with TWO MEMBER people, both with mobiles | 200; exactly ONE text, to the primary contact (the attribution rule) |
| 9c | admin → `.../sms-reminder` for a member whose resolved RENEWAL method is NONE | 409 `PREFERENCE_NONE`, nothing in the ring |
| 10 | admin → POST `.../sms-reminder` for a member with no MOBILE (or only an unnormalisable one) | 409 `NO_NUMBER`, nothing in the ring |
| 11 | inbound: POST `/api/sms/inbound?secret=…` {from=row 9's mobile in `04…` form, body="STOP"}; then re-POST identically (provider redelivery) | 200 both; psql: exactly ONE `sms_opt_out` row, key = the E.164 form; `communication_preference` untouched (email unaffected) |
| 11b | inbound with a bad/missing secret; and (manual leg, no secret configured anywhere) | 403; 503; no row either way |
| 11c | after row 11: `.../sms-reminder` for that member AND `.../test` to that number | 409 `OPTED_OUT` / `{ok:false}` refused — the `Sms.send` chokepoint gates every path; nothing in the ring |
| 12 | `Sms.enabled()` gate: DELETE the row **and** (manual) run a cargo with no SMS env → GET `source=NONE`, `.../test` and `.../sms-reminder` answer 503; the Renewals Text action disabled | 503 / disabled |
| 13 | psql | exactly one `sms_settings` row after the PUTs (upsert, not append); then cleaned up — end state: no `sms_settings` row, empty `sms_opt_out` |

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
   the number-keyed register makes this natural: insert `sms_opt_out`
   only if absent (the PK is the guard — the StripeWebhook redelivery
   discipline, `ON CONFLICT DO NOTHING`).
5. **Short connect timeout on the provider `HttpClient`** (a few
   seconds) — row 7 points a send at a closed port, and the JDK default
   would stall that matrix row (and any admin test-send against a dead
   host) for minutes.

Mechanics / house patterns:

- `Sms` is a static utility using `Db.jdbi()` for `resolve()`, no DI, no
  cached `Settings` — per-use resolution is the design (mid-flow pickup),
  as with `Mail`.
- New files: the V8 `sms_opt_out` migration, `Sms`, `SmsProvider`
  (+ `CLICKSEND`/`MOBILE_MESSAGE`/`LOG` arms), `AdminSmsSettingsResource`,
  `SmsInboundResource`, the reminder method on
  `AdminMembershipsResource`, `admin/sms-settings.html` + `admin.js`
  wiring + the menu entry. Register the new resources in `ApiApplication`
  (explicit, no scanning).
- The LOG ring is a fixed-size in-memory deque — per-JVM, empty after a
  cargo restart. Fine for the single-node dev stack the matrix runs
  against; each matrix row reads `GET .../log` immediately after its
  send.
- Reuse `RenewalTokenStore` for `{{payLink}}` (fresh token per send) and
  `CommunicationPreferenceStore.resolve` for channel selection — no new
  copies of either rule.
- `CR16-*` rows self-clean (no `sms_settings` row, empty `sms_opt_out`)
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
- **START / self-serve re-subscribe** — v1's un-opt-out is an admin
  deleting the `sms_opt_out` row on the member's request; automatic
  START handling rides the same inbound seam when it's earned.
- **MMS / delivery-receipt ingestion / two-way conversations** beyond
  STOP — not earned at this volume.
- **Mobile-coverage report** — an admin count of ACTIVE members with vs
  without a usable MOBILE, to size how many SMS can actually reach before
  the society commits. Cheap; a good pre-flight but not blocking.

## Close-out (on implementation)

- `CLAUDE.md`: an architecture paragraph (SMS as the first actioner of
  `phone_number`/`communication_preference` SMS; `Sms` mirrors `Mail`'s
  resolve-per-use; the number-keyed `sms_opt_out` register + `Sms.send`
  chokepoint; the two-tier consent rule;
  dedicated-number/Sender-ID-Register rationale; best-effort discipline)
  and the dev-command note for `SMS_PROVIDER=LOG`.
- `README` / `GETTING-STARTED`: SMS configuration section — the page
  first, env as bootstrap/dev, the LOG provider for local runs.
- `docs/ROADMAP.md`: CR-016 row.
