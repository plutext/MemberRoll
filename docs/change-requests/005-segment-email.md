# CR 005: Segment email — templates, merge fields, send log, communication preferences

Status: VERIFIED (2026-07-18)

## Problem

After CR-004 a member who *has* a pay link can pay online, but nothing
sends those links out. The treasurer's renewal season is still manual:
mint a pay link per membership in the admin panel, paste it into a
personal email, repeat ~100 times. The roadmap sequenced email after
Stripe precisely so the first renewal email could carry a pay-now link
(ordering principle, ROADMAP.md).

Roadmap scope (CR-005): templates, merge fields (name, amount, magic
link), segment sends, send log, `CommunicationPreference` honoured,
"these N members have no email" surfaced rather than silently skipped.
Decision 2026-07-18 adds the communication-preferences admin UI to this
CR: default EMAIL when no row exists, POST exceptions entered by hand
(the table shipped empty in CR-001 and nothing manages it today).

Constraints already decided: synchronous SMTP relay, per-recipient
sends, no queue at this scale (decision 2026-07-17); `Mail` stays
env-configured and optional (SMTP unset → app still works); mint is
fresh-per-call so per-email minting is safe and older links stay valid
(CR-004 amendment).

## Approach

### What a "segment" is

Not a new concept: a segment is exactly the CR-003 financial-status
view — a period plus the same optional filters its list endpoint
already takes (`status`, `type`). "Unpaid for 2026–2027" is
`periodId + status=PENDING_PAYMENT`; "everyone current" for a
newsletter is `periodId + status=ACTIVE`. The send resource resolves
the segment to membership rows with the same query
`GET /api/admin/periods/{id}/memberships` uses, so what the admin sees
in the financial table is literally what the send targets. No saved
segments, no query builder.

### Recipient resolution (segment rows → addresses)

Per membership in the segment:

1. Candidate people = the household's **current** members
   (`left_household_date IS NULL`) with `relationship_type` **MEMBER
   only** (decided 2026-07-18, see the answered questions below).
   PARTNER/DEPENDANT/OTHER never receive segment mail — consistent
   with the voting-rights correction: the formal member is the one the
   society corresponds with.
2. For each candidate, the applicable preference for the send's
   `communication_type`: the person's current row if any, else the
   household's current row, else the default **EMAIL** (decision
   2026-07-18). `delivery_method` EMAIL → include with the person's
   primary email; POST → skip, logged `SKIPPED_POST` (the treasurer
   handles these via the CR-003 mailing-labels export); NONE → skip,
   logged `SKIPPED_NONE`. SMS is not implemented (roadmap out of
   scope) and treated as NONE with a warning in preview.
3. **Dedup within the membership by address** (couples share an email
   — schema-doc rule; a HOUSEHOLD membership can hold two MEMBERs, so
   this still bites; the household's primary contact wins attribution,
   else the lower person id). The same address on two *different*
   memberships gets one email per membership: the pay links differ,
   that is correct.
4. A membership with zero includable addresses is logged `NO_EMAIL` —
   one row per membership, surfaced in preview and permanently in the
   send log, never silently skipped (roadmap requirement).

### Schema (V5 migration)

The schema doc reserved "the email send log (CR-005)" as a known later
addition. Three tables, insert-only in spirit:

```sql
CREATE TABLE email_template (
    email_template_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text NOT NULL UNIQUE,
    subject     text NOT NULL,
    body        text NOT NULL,          -- plain text with {{mergeFields}}
    updated_by  text NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE email_send (
    email_send_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email_template_id    bigint REFERENCES email_template ON DELETE SET NULL,
    subject              text NOT NULL,  -- snapshots: the log shows what was
    body                 text NOT NULL,  -- SENT, template edits can't rewrite it
    membership_period_id bigint NOT NULL REFERENCES membership_period,
    status_filter        text,           -- the segment, as the list-endpoint params
    type_filter          bigint REFERENCES membership_type,
    communication_type   text NOT NULL
                         CHECK (communication_type IN ('NEWSLETTER', 'RENEWAL', 'EVENTS', 'GENERAL')),
    status               text NOT NULL DEFAULT 'RUNNING'
                         CHECK (status IN ('RUNNING', 'COMPLETE', 'ABORTED')),
    created_by           text NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    finished_at          timestamptz
);

CREATE TABLE email_send_recipient (
    email_send_recipient_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email_send_id    bigint NOT NULL REFERENCES email_send ON DELETE RESTRICT,
    membership_id    bigint NOT NULL REFERENCES membership,
    person_id        bigint REFERENCES person,      -- NULL only for NO_EMAIL rows
    email            text,                          -- as resolved at send time
    status           text NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'SENT', 'FAILED',
                                       'SKIPPED_POST', 'SKIPPED_NONE', 'NO_EMAIL')),
    error            text,
    renewal_token_id bigint REFERENCES renewal_token,
    sent_at          timestamptz,
    CHECK ((status = 'NO_EMAIL') = (email IS NULL))
);
CREATE INDEX email_send_recipient_send ON email_send_recipient (email_send_id);

-- first app-level setting (the saved footer); generic on purpose
CREATE TABLE app_setting (
    key        text PRIMARY KEY,
    value      text NOT NULL,
    updated_by text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
```

No migration touches `communication_preference` — CR-001's table is
already right; this CR just starts writing to it.

### Templates and merge fields

Plain text only (see rejected alternatives). Subject and body both
support `{{field}}` merge fields:

| field | renders as |
|---|---|
| `{{givenName}}` / `{{familyName}}` | the recipient person |
| `{{displayName}}` | household name, else primary contact's full name (the CR-004 pay-view rule) |
| `{{periodName}}` / `{{typeName}}` | e.g. `2026-2027` / `Household` |
| `{{amountDue}}` / `{{amountPaid}}` / `{{balance}}` | formatted dollars, e.g. `$65.00` |
| `{{payLink}}` | a freshly minted magic link for the membership |
| `{{societyName}}` | `MEMBERROLL_SOCIETY_NAME` |

**Footer** (decided 2026-07-18): a standard footer (society contact
details, "reply to switch to post / stop receiving these") is stored
globally in `app_setting` under key `email_footer` and appended to
every send's body. The compose form shows it prefilled and editable
per-send, with a "save as the default footer" checkbox — so a one-off
tweak doesn't have to become the standard, but can. The footer text
supports the same merge fields (`{{societyName}}` mainly) and the same
strict validation; the body *snapshot* in `email_send` includes the
footer as sent, so the log needs no separate footer column. Test
sends and previews include it.

Rendering is **strict**: an unknown `{{field}}` is a 400 at template
save AND at send creation (belt and braces — a template written before
a rename must not leak `{{payLnk}}` into 100 mailboxes). `{{payLink}}`
mints per recipient row at send time via `RenewalTokenStore.mint`
(fresh-per-call is fine, CR-004 amendment), and the minted
`renewal_token_id` lands in the recipient row — the log can answer
"which token did we email whom".

### Send execution — sequential on the mail thread, no queue

`POST /api/admin/email/sends` runs the resolution above in one
transaction: insert the `email_send` (subject/body snapshot) and ALL
recipient rows (PENDING plus the SKIPPED_*/NO_EMAIL bookkeeping), then
return 201 with the send id. The actual sending happens on `Mail`'s
existing single sender thread (`Mail.async`): for each PENDING row —
mint token, render, `Mail.send`, update the row SENT/FAILED(+error) in
its own short transaction. The UI polls `GET .../sends/{id}` for
progress. This honours the "synchronous relay, no queue" decision: no
scheduler, no retry machinery, one thread, at ~1s/mail even 1000
recipients finish inside 20 minutes.

Guard rails:

- **One RUNNING send at a time** — a second POST answers 409. Prevents
  the double-click double-send, and the single thread means a second
  batch would just sit behind the first anyway.
- **Abort on 5 consecutive failures** — a dead relay must not grind
  through 995 more 10-second timeouts. The send flips to ABORTED,
  remaining rows stay PENDING.
- **Resume, not re-send**: `POST .../sends/{id}/resume` re-enqueues
  that send's PENDING and FAILED rows only — SENT rows are never
  re-sent. This is also the recovery for a JVM restart mid-send (rows
  stranded PENDING under a RUNNING send with a dead thread: resume
  flips it back to life). No automatic retry, ever — the treasurer
  presses the button.
- **Test send**: `POST .../templates/{id}/test {to}` renders with
  obviously-fake sample data (`Alex Example`, `$65.00`, a
  `[pay link appears here]` placeholder — no token is minted) and
  sends one mail. How templates get proofread without touching members.

SMTP unconfigured: sends are still *created* (the resolution and log
are useful dry-run output) but every attempt FAILs with "mail
disabled" — consistent with `Mail`'s existing contract. Preview (below)
is the intended dry-run, so the UI also shows a "mail is not
configured" banner from the existing health signal.

### Admin API

All `@RolesAllowed("admin")`, on a new `AdminEmailResource` +
`EmailStore` (CR-003 pattern: store methods take the `Handle`, the
resource owns transactions):

| method | path | behaviour |
|---|---|---|
| GET/POST | `/api/admin/email/templates` | list / create (validates merge fields; duplicate name 409) |
| PUT/DELETE | `/api/admin/email/templates/{id}` | update (validated) / delete (past sends keep their snapshot; FK is SET NULL) |
| POST | `/api/admin/email/templates/{id}/test` | `{to}` → one mail with sample data |
| GET/PUT | `/api/admin/email/footer` | the saved global footer (`{text}`); PUT validates merge fields |
| POST | `/api/admin/email/preview` | `{templateId, periodId, statusFilter?, typeFilter?, communicationType, footer?}` (footer defaults to the saved one) → the full resolution *without writing anything*: counts + per-category lists (to-send with addresses, skipped-post, skipped-none, no-email) + the first recipient's rendered subject/body |
| POST | `/api/admin/email/sends` | same body → 201 `{id}`; 409 if one is RUNNING; sending starts on the mail thread. "Save as default footer" is the UI calling PUT footer, not a send parameter |
| GET | `/api/admin/email/sends` | history, newest first, with per-status counts |
| GET | `/api/admin/email/sends/{id}` | the send + its recipient rows (status, email, person, error) — this is the send log |
| POST | `/api/admin/email/sends/{id}/resume` | re-enqueue PENDING+FAILED; 409 if RUNNING |

Preferences ride the existing person/household resources, backed by a
new `CommunicationPreferenceStore`:

| method | path | behaviour |
|---|---|---|
| GET | `/api/admin/people/{id}/preferences` (and `/api/admin/households/{id}/preferences`) | current rows, keyed by communication_type; absent = EMAIL default |
| PUT | same | `{communicationType, deliveryMethod}` → closes the current row (`effective_to = now`) and inserts the new one — history preserved, the insert-don't-overwrite house rule. `deliveryMethod: "EMAIL"` with no household/inherited exception simply deletes nothing and inserts nothing if it equals the effective default (no row churn for the common case) |

`consent_status` stays untouched (NULL) — it exists for CR-007's
opt-in flows, and writing it here would invent semantics early.

### Admin UI

New `admin/email.html` (own page, like `users.html`/`import.html` —
`index.html` is big enough), on the shared menu/Pico baseline:

- **Templates**: table + dialog editor with the merge-field list shown
  beside the textarea; Test-send button (prompts for an address,
  defaults to the logged-in admin's email).
- **Compose**: template picker, period picker, status/type filters
  mirroring the Renewals tab, communication-type picker (default
  RENEWAL), and the footer textarea (prefilled from the saved footer,
  editable, "save as the default footer" checkbox). **Preview**
  renders the counts, the four lists, and the sample mail (footer
  included). **Send** is enabled only after a preview of the same
  parameters and confirms with the recipient count in the button label
  ("Send 87 emails").
- **Log**: sends table (date, template, segment, counts, status) with
  drill-in to recipient rows; Resume button on ABORTED/stuck sends.

Preferences UI (decision 2026-07-18: manual POST exceptions): in
`index.html`'s existing household-detail dialog, a small preferences
table (communication type × delivery, defaults greyed), and the same
control per person via the person form. Entered by hand for the
handful of post members; no re-import.

### Config

No new required env — SMTP/`MAIL_FROM`/`MEMBERROLL_SOCIETY_NAME`/
`PUBLIC_BASE_URL` all arrived in CR-004. One optional addition:
`MAIL_REPLY_TO` (renewal replies should reach the treasurer, not
`noreply@`); unset → no Reply-To header, exactly today's behaviour.

### Rejected alternatives

- **HTML templates** — plain text is what a ~100-member historical
  society's volunteers can safely edit, renders everywhere, and skips
  the multipart/deliverability surface. The pay link is a bare URL,
  which mail clients auto-link. An HTML wrapper can come later without
  schema change (add a column), if ever.
- **A queue / scheduler / retry-with-backoff** — explicitly decided
  against at this scale (2026-07-17). The failure story is a visible
  ABORTED send and a Resume button, not a background daemon.
- **Saved segment definitions** — a segment is three dropdowns the
  admin already understands from the Renewals tab; persisting the
  *chosen* filters on the send row is record enough.
- **Per-send token reuse ("don't mint if a live token exists")** —
  unimplementable under hash-only storage (CR-004 amendment) and
  unnecessary: fresh mint per email, older links stay valid.
- **Sending on the request thread** — 100 × ~1s sends blows any
  sensible request timeout; the existing mail thread + polling is the
  smallest thing that works.
- **A `mergeFields` DSL beyond `{{name}}`** (conditionals, loops) —
  the one variable-shape case (household vs single) is already handled
  by `{{displayName}}`/`{{typeName}}`; anything smarter is a template
  language, which is a dependency this war doesn't need.
- **Unsubscribe links** — renewal notices are transactional
  (existing-relationship, factual) under the Spam Act; NEWSLETTER/
  GENERAL sends go only to current members who can reply to be set to
  NONE by the admin (the preferences UI is the mechanism). An
  automated unsubscribe endpoint is CR-007+ territory if the society
  ever emails beyond its membership.

## Verification plan

Scripted (extend `verify-matrix.sh` with CR5-* rows; fixtures under
`tmp/cr005-fixtures/`). Mailpit's REST API (`:18025/api/v1/messages`)
lets the script assert delivered mail bodies, and the SMTP-failure
rows get real failures by stopping the Mailpit container mid-matrix
(`docker compose stop mailpit` … `start`), the same
control-the-environment trick CR-004 used for self-signed webhooks.

Fixture people (seeded via API): household A — HOUSEHOLD membership,
two MEMBER adults sharing one address (dedup case) plus a PARTNER
with their own distinct email (exclusion case); household B — member
with own email, person preference RENEWAL=POST; household C — no
email at all; household D — household-level preference GENERAL=NONE,
person override RENEWAL=EMAIL (precedence case). All PENDING_PAYMENT
in the current period.

| # | caller | call | expect |
|---|---|---|---|
| 1 | guest / testuser / test-cli-noaud | GET /api/admin/email/templates | 401 / 403 / 401 |
| 2 | testadmin | POST templates {name, subject, body with {{givenName}},{{balance}},{{payLink}}} | 201 |
| 3 | testadmin | POST templates, body contains {{payLnk}} | 400 naming the bad field |
| 4 | testadmin | POST templates, duplicate name | 409 |
| 5 | testadmin | PUT /api/admin/people/{B}/preferences {RENEWAL, POST} | 200; GET echoes; psql: old row effective_to set, new row inserted |
| 6 | testadmin | PUT /api/admin/households/{D}/preferences {GENERAL, NONE} + person override {RENEWAL, EMAIL} | 200; GET on person shows RENEWAL=EMAIL effective |
| 7 | testadmin | POST preview {template, period, status=PENDING_PAYMENT, RENEWAL} | 200: A deduped to 1 recipient with A's PARTNER address appearing **nowhere** (MEMBER-only rule), B listed skipped-post, C listed no-email, D included (person override beats household NONE); sample render has real name, `$` amount, a pay URL, the footer |
| 8 | testadmin | POST preview, communicationType=GENERAL | D excluded (household NONE applies — no person override for GENERAL) |
| 9 | guest / testuser | POST /api/admin/email/sends | 401 / 403 |
| 10 | testadmin | POST sends (params from #7) | 201 {id}; poll GET sends/{id} → COMPLETE; recipient rows: SENT for A(1)+D(1), SKIPPED_POST for B, NO_EMAIL for C |
| 11 | — | Mailpit | exactly 2 messages; body has the member's given name, the balance, a pay URL, and ends with the footer; From = MAIL_FROM, Reply-To = MAIL_REPLY_TO |
| 12 | guest | GET /api/pay/{token from a sent mail's URL} | 200, correct membership — the emailed link actually works |
| 13 | — | psql | each SENT row has renewal_token_id; token expires per CR-004 rule |
| 14 | testadmin | edit the template's body, then GET sends/{id} | send still shows the ORIGINAL subject/body (snapshot) |
| 15 | testadmin | POST templates/{id}/test {to: probe@example.org} | 200; Mailpit: one mail with sample data, no token minted (psql count unchanged) |
| 16 | — | docker compose stop mailpit; testadmin POST sends (segment of ≥6) | send → ABORTED after 5 consecutive FAILED, remaining PENDING |
| 17 | testadmin | POST sends/{id}/resume after compose start mailpit | 200; poll → COMPLETE; previously-FAILED and PENDING now SENT; earlier SENT rows not duplicated in Mailpit |
| 18 | testadmin | POST a second send while one is RUNNING | 409 (use the stopped-Mailpit send from #16 as the running one) |
| 19 | testadmin | DELETE templates/{id} used by a past send | 200; GET sends/{id} intact (snapshot, FK null) |
| 20 | testadmin | GET /api/admin/email/sends | history row with per-status counts matching #10 |
| 21 | testadmin | PUT /api/admin/email/footer {text with {{societyName}}} | 200; GET echoes; PUT with {{bogus}} → 400 |
| 22 | testadmin | POST sends with an inline `footer` differing from the saved one | mail carries the inline footer; GET footer still returns the saved one (per-send override doesn't save) |

Browser walkthrough (dev stack + Mailpit UI):

1. Create a renewal template with every merge field; edit the footer
   on the compose form and save it as the default; test-send; read it
   in Mailpit (footer present).
2. Set one person's RENEWAL preference to POST in the household
   dialog.
3. Compose: current period, Unpaid, RENEWAL → preview shows the
   counts and the excluded lists (the POST person, the no-email
   household) → Send → watch the log fill → open a mail in Mailpit →
   click its pay link → the CR-004 pay page renders correctly (through
   to a test-card payment if a Stripe key is on hand).
4. Stop Mailpit, send again, watch it abort; start Mailpit, Resume,
   watch it complete without re-sending step 3's mails.
5. Phone check of the email flow is N/A (mail client), but open one
   emailed pay link on a phone via the LAN IP (the CR-004 issuer/PKCE
   rules don't apply — no login — but the URL must be reachable).

No auth/Caddy/compose changes → deploy Local smoke not triggered by
this CR (SPF/DKIM and production from-address remain CR-008).

## Notes for the implementing session

- **Build the CR5-* matrix rows alongside each endpoint, not as a
  batch at the end.** The rows that stop/start the Mailpit container
  (#16–18: abort, resume, one-RUNNING-send) have fiddly choreography —
  the send must be mid-flight when Mailpit dies, and the resume
  assertion depends on Mailpit's message count from the *earlier*
  rows — and that is much easier to get right while the endpoint's
  behaviour is fresh than to retrofit.
- **Do a headless-browser (Playwright) pass on the compose form, not
  just the API matrix.** CR-010 precedent: its worst UI bug (a
  `<dialog>` auto-opening on page load and stealing modal focus) was
  invisible to the API matrix and only caught in a headless browser.
  The equivalent risks here: the Send button's enabled-only-after-a-
  matching-preview rule, the footer textarea prefill vs the
  save-as-default checkbox, and preview-list rendering.

## Open questions — answered 2026-07-18 (Jason), design amended in place

- **Who in a household receives the renewal notice**: current
  **MEMBER-relationship people only** — PARTNER does not receive
  segment mail (the original proposal said MEMBER+PARTNER; the
  schema-doc "separate renewal notices to each member" example is
  satisfied because a HOUSEHOLD membership can hold two MEMBERs).
  Recipient-resolution step 1 and the fixtures reflect this.
- **Reply-to**: configurable — the optional `MAIL_REPLY_TO` env var as
  designed; the actual address is a deploy-time (CR-008) concern.
- **Footer**: stored globally (`app_setting`), editable on the compose
  form per-send with a "save as the default" option — designed in
  above. Actual wording still to be drafted with the society before
  the first real send.
- **Rollout**: first real sends are RENEWAL-typed only; *Yandoo*
  NEWSLETTER sends can follow whenever the society wants (no design
  impact).

## Results

Implemented 2026-07-18. Status: **VERIFIED**.

### What shipped

- **V5 migration** (`V5__email_send_log.sql`): `email_template`, `email_send`,
  `email_send_recipient`, `app_setting` exactly as designed; no change to
  `communication_preference` (CR-001's table, now first written to).
- **Backend**: `MergeFields` (closed vocabulary + strict validate/render),
  `CommunicationPreferenceStore` (person → household → EMAIL resolution,
  close-current-then-insert writes, no churn when a value equals the inherited
  default), `EmailStore` (templates, footer via `app_setting`, segment
  resolution with MEMBER-only + per-address dedup + NO_EMAIL, send-log reads,
  and the sequential sender on `Mail`'s single thread with abort-after-5 and
  resume), and `AdminEmailResource` (`/api/admin/email/*`). Preferences ride
  the existing person/household resources (`GET`/`PUT .../preferences`).
  `Mail` gained the optional `MAIL_REPLY_TO` header.
- **Admin UI**: new `admin/email.html` (templates, compose+preview+send, send
  log with drill-in and Resume) wired in `admin.js`; a preferences table in
  the household detail dialog and the (editing-only) person form; an "Email"
  menu entry.

### Scripted matrix

`server/verify-matrix.sh` extended with 75 CR5-* assertions (rows CR5-01…22).
Full suite **PASS=389 FAIL=0** against the dev stack with the CR-004 mail env
(`SMTP_HOST=localhost SMTP_PORT=18026 MAIL_FROM=noreply@memberroll.dev
MAIL_REPLY_TO=treasurer@memberroll.dev MEMBERROLL_SOCIETY_NAME=…`), re-run
green twice consecutively (the abort/resume rows stop and start the Mailpit
container mid-run). Notable coverage matching the plan:

- Auth (guest/user 403, noaud 401); template create + `{{payLnk}}` → 400
  naming the field; duplicate name → 409; footer validate + bogus-field 400.
- Preferences: person RENEWAL=POST recorded (one current row, source=person);
  household GENERAL=NONE with a person RENEWAL=EMAIL that resolves EMAIL while
  GENERAL still inherits NONE (source=household).
- Preview RENEWAL: 4 memberships → household A deduped to **1** address with
  the PARTNER address appearing **nowhere**, B skipped-post, C no-email, D
  included; sample carries a real name, a `$` amount and a pay URL. Preview
  GENERAL: D excluded (household NONE), A still included.
- Send: COMPLETE with 2 SENT / 1 SKIPPED_POST / 1 NO_EMAIL; Mailpit shows mail
  to the two members and **none** to the partner or the post member; body
  carries the given name, footer and pay URL; `From`=`MAIL_FROM`,
  `Reply-To`=`MAIL_REPLY_TO`; the emailed link resolves (`GET /pay/{token}` →
  200, right membership); every SENT row has a `renewal_token_id`.
- Snapshot: a template edit leaves the send's subject/body unchanged; deleting
  a used template keeps the snapshot and nulls `templateName`.
- Test-send delivers sample data (`[pay link appears here]` placeholder) and
  mints no token.
- Abort/resume: with Mailpit stopped a 6-recipient send goes ABORTED after 5
  consecutive FAILED (1 left PENDING); a second POST while a send is RUNNING →
  409; after Mailpit restarts, Resume completes all 6 with no duplicate to the
  already-sent addresses. Inline footer overrides per-send without touching the
  saved footer; history lists the send with per-status counts.

### Headless browser (Playwright)

A Chromium pass on `email.html` (login bypassed by injecting an admin token
into `localStorage`) confirmed the UI-only behaviours the API matrix can't see:
the section renders past login with no asset/console errors; the footer
prefills from the saved default and the "save as default" box starts unchecked;
merge-field chips insert `{{tokens}}` into the focused body; a template saves
and lists; **Send is disabled until a preview of the exact same parameters**
and re-locks on any compose or footer change; preview renders the to-email /
no-email lists and a sample mail containing a pay URL; the Send label shows the
recipient count ("Send 1 email"). 12/12 behaviours passed (a lazily-requested
`favicon.ico` 404 is pre-existing across every admin page, not a regression).

## Follow-ups / amendments

(dated additions after field feedback)
