# CR 007: Public application form — APPLIED workflow with committee approval

Status: PROPOSED (2026-07-23; pre-proposal notes 2026-07-18; the
entrance-fee blocker was resolved by the committee 2026-07-23 — see
Follow-ups)

## Problem

Roadmap item 8: a public new-member application form on the society's
website → APPLIED → approval → payment. Everything downstream exists
(CR-010's composite member creation, CR-004's pay links, CR-005's
email); what is missing is the guest-facing intake and the approval
step.

## Constitutional constraints (Constitution, updated 6/2024)

Read against the constitution on 2026-07-18; the design must hold
these:

1. **Approval is a committee decision, not an admin click** (clause
   3(3)–(4): the secretary refers applications to the committee, which
   must approve or reject). The app's approve/reject action *records*
   the committee's decision — carry a decision date and an optional
   minute reference — and there is never an auto-approval path.
2. **No money is collected at application time.** Clause 3(5)(b):
   on approval the applicant is notified and must pay within **28
   days**; if an application is rejected, any fees already paid must be
   refunded — so the form takes no payment and the approval notice
   carries the CR-004 pay link instead. Consider an "approved but
   unpaid > 28 days" aging view for the admin.
3. **Membership begins at payment + register entry**, not approval
   (clause 3(6)–(7)) — matching the existing APPLIED →
   PENDING_PAYMENT → ACTIVE progression, so approval materializes a
   PENDING_PAYMENT membership (ACTIVE only when paid).
4. **The decision notice is a deliverable** (clause 3(5)(a)): written
   notice of approval/rejection, email permitted (clause 41).
5. **A household application is one clause-3 application per adult
   applicant** — approval must name and cover each person who is to
   become a formal (relationship_type MEMBER) member; a PARTNER
   under clause 2(2) is not an applicant for membership.
6. **Governance precondition** (clause 3(1)(b), 3(2)): electronic
   lodgement, and the form itself, must be determined by the committee
   — the society should minute that the website form is the approved
   application form before this CR goes live.

## Design leanings carried over from the 2026-07-18 assessment

Kept as history; each is resolved in the Approach (the one divergence:
the spam posture drops the per-IP limit for a global backstop — see
Rejected alternatives).

- Applications land in a **staging table**, not the register — the
  register is never-delete and has statutory meaning (clause 4), so
  junk submissions must be deletable; approval materializes rows via
  the CR-010 composite-creation path.
- Spam posture without external dependencies: honeypot + per-IP rate
  limit + an email-confirmation round trip before the application
  reaches the queue (reusing CR-006's mailbox-control trust anchor; a
  confirmed application email can count as verified for later
  provisioning).
- The approval screen should support matching an applicant to an
  existing person/household (lapsed member re-applying) vs creating
  new — or at minimum flag likely duplicates (CR-002/CR-010
  precedents).
- New-application notification to a configured society address —
  answered 2026-07-23: `membership@yasshistory.org.au` (a shared
  mailbox), and it must be settable/alterable on an admin config page
  (the CR-014 `app_setting` pattern), not baked into env or code.
- Whether approval auto-runs CR-006 provisioning stays as decided in
  CR-006: deferred; the admin presses the button.

## Outstanding issues

- **Entrance fee — RESOLVED (2026-07-23).** Clause 5(1) prescribes a
  $20 entrance fee for approved applicants, "or another amount
  determined by the committee", on top of the annual subscription.
  The committee has set the entrance fee to **zero (0)** and confirmed
  the system need not accommodate one — no entrance-fee billing shape
  is required; the approval flow bills the annual subscription only.
  (Original 2026-07-18 deferral: whether a fee applied was unknown, and
  a non-zero fee would have needed a one-off allocation line or a
  first-year price.)
- **Governance precondition still open**: the committee acknowledged
  (2026-07-23) that before the form goes live it must formally resolve
  and minute that the website form is the society's approved
  application form and permit electronic lodgement (clause 3). Not yet
  minuted — this gates go-live, not the build. The Approach turns this
  gate into a switch: the form ships **disabled** (`formEnabled`
  defaults false) and is turned on from the admin page once the minute
  exists — code can deploy ahead of the resolution.

## Approach

The feature is three surfaces over one new staging pair: a guest
application form (submit + email-confirm), an admin queue (review,
approve/reject, delete junk), and a small settings blob (alert mailbox
+ the form on/off switch). Approval is the only place register rows are
created, and it goes through the CR-010 composite path under the CR-010
transaction discipline.

### The staging record — V10 migration

Applications must be deletable (junk, spam, test submissions), so they
never touch the register (clause 4 is never-delete). Two new tables:

```sql
membership_application (
    application_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status              text NOT NULL CHECK (status IN
                          ('RECEIVED', 'CONFIRMED', 'APPROVED', 'REJECTED')),
    submitted_at        timestamptz NOT NULL DEFAULT now(),
    submitted_ip        text,                    -- RemoteIpValve-corrected; spam forensics
    confirm_token_hash  text NOT NULL,           -- sha256 hex only, CR-004 pattern
    confirm_expires_at  timestamptz NOT NULL,    -- 7 days
    confirmed_at        timestamptz,
    membership_type_id  bigint NOT NULL REFERENCES membership_type,  -- requested; advisory
    address_line_1      text,                    -- optional postal address,
    address_line_2      text,                    -- mirrors household_address
    locality            text,
    state               text,
    postcode            text,
    applicant_message   text,
    decision_date       date,                    -- the committee's, not the click's
    minute_reference    text,
    rejection_reason    text,                    -- internal only, never emailed
    decided_by          text,                    -- admin username (audit)
    created_household_id  bigint REFERENCES household,
    created_membership_id bigint REFERENCES membership,
    CHECK ((status IN ('APPROVED','REJECTED')) = (decision_date IS NOT NULL))
);

membership_application_person (
    application_person_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id      bigint NOT NULL REFERENCES membership_application
                          ON DELETE CASCADE,     -- junk delete is one statement
    position            smallint NOT NULL,       -- 1 = the submitting applicant
    given_name          text NOT NULL,
    family_name         text NOT NULL,
    email               text,                    -- required for position 1
    phone               text,
    relationship        text NOT NULL DEFAULT 'MEMBER' CHECK (relationship IN
                          ('MEMBER', 'PARTNER', 'DEPENDANT', 'OTHER'))
);
```

One row per named person keeps clause 5 visible in the data: each
`relationship = MEMBER` row IS a clause-3 applicant; a PARTNER row is a
covered person, not an applicant. v1 accepts at most two people
(mirroring the CR-010 wizard). Lifecycle: RECEIVED (awaiting the email
round trip, invisible to the queue's default view) → CONFIRMED (in the
queue) → APPROVED / REJECTED (terminal, with the decision fields set).

Note the register's `APPLIED` membership status — present in V1's CHECK
constraint, written by no code — **stays unused**: the roadmap's
"APPLIED workflow" is delivered by this staging table instead, exactly
so that a pre-approval application is deletable and the register never
holds one. Approval materializes straight to PENDING_PAYMENT (or ACTIVE
for a zero-due type, rule 6).

### Guest surface — `ApplyResource` + `web/apply.html`

`ApplyResource` (`@Path("apply")`, registered in `ApiApplication`, no
`@RolesAllowed` — the PayResource precedent; AuthFilter passes
guests through):

- **`GET /api/apply/options`** → `{open, societyName, types:
  [{id, name, priceCents, minimumPeople, maximumPeople}]}`. Types are
  the **positively-priced** types of the period covering today (else
  the next-starting period) — a $0 type (LIFE) is never publicly
  applicable. Prices here are display-only; the authority is the
  admin's period/type choice at approval. `maximumPeople` drives the
  second-person UI client-side, the CR-010 mirroring pattern. `open`
  reflects `formEnabled` so the page can render the closed message.
- **`POST /api/apply`** → 202 `{message: "check your email…"}`.
  Validates (400): position-1 given/family/email present, email
  contains `@`, known positively-priced type, second person only when
  the type allows. Writes the RECEIVED row + people, mints the confirm
  token (256 random bits, base64url, sha256-only stored — the CR-004
  recipe), sends the confirmation email. Refuses 503 when the form is
  off (`formEnabled` false) or `Mail.enabled()` is false — without
  mail there is no round trip, so the flow is honestly unavailable
  (the checkout-503 convention).
- **`POST /api/apply/confirm`** `{token}` → 200 on first confirmation
  (row → CONFIRMED, `confirmed_at` stamped, the admin alert fires) and
  200 again on repeats (idempotent — double clicks and mail-scanner
  page loads are harmless; the alert fires only on the first). Unknown
  and expired tokens are an indistinguishable 404 (CR-004 parity — no
  oracle).

`web/apply.html` + `apply.js` copy `pay.html` exactly: Pico classless
CSS, **no auth.js** (the page never logs in), fetches without an
Authorization header. The form: membership type (from options), the
applicant (given/family required, email required, phone optional), the
optional second person with a plain-language relationship choice ("also
applying for membership" = MEMBER, joint applicant with voting rights,
vs "covered by the membership" = PARTNER), optional postal address
(the five `household_address`-shaped fields), optional message, and the
honeypot. The confirmation link lands on `apply.html?confirm=<token>`;
the page POSTs the token and shows the result. The email round trip is
the same mailbox-control trust anchor CR-006 provisioning uses — a
confirmed application address later counts as verified for free,
because provisioning already treats register emails as verified.

The second person is named by the submitter without their own round
trip — the same property as a paper form handed in by one spouse. The
committee's human review at approval is the check; clause 5 is
satisfied because the approval names each MEMBER person.

### Spam posture — no external dependencies

Three layers, all in-process:

1. **Honeypot**: a visually-hidden field; when filled, answer the same
   202 and write nothing (log it). Bots see success.
2. **Per-email cooldown**: 10 minutes per submitting address, the
   `PayResource.LOST_LINK_RECENT` in-memory pattern verbatim (bounded
   map, 429 when tripped). Matrix fixtures use `$$`-unique addresses so
   re-runs never collide with it.
3. **Global backstop**: an in-memory cap of 30 submissions per rolling
   hour → 429. This society receives a handful of applications a
   month; anything resembling volume is a flood, and a global cap
   catches it without discriminating by IP.

The gate that actually keeps junk out of the admin's way is the
**confirmation round trip**: RECEIVED rows never appear in the queue's
default view, so an unconfirmed spam submission costs nothing but a
row (deletable, and see Follow-ups for bulk purge).

### Alerts + settings — `application_settings`

One new `app_setting` JSON blob (key `application_settings`, the CR-014
one-atomic-value pattern), read per use, never cached:

```json
{"alertMailbox": "membership@yasshistory.org.au", "formEnabled": true}
```

`GET`/`PUT /api/admin/application-settings` (admin-only) with a
settings card on the applications admin page — the committee's
requirement that the society mailbox be page-settable, in the place an
admin managing applications will look. Absent row = form **disabled**,
no alert mailbox. `formEnabled` is the clause-3 go-live switch (see
Outstanding issues).

On first confirmation, one transactional `Mail.sendAsync` (the CR-012
pattern — never the CR-005 segment machinery) notifies the
`alertMailbox`; when it is unset, the fallback is the **current
secretary's** email from the CR-013 contacts seam
(`CommitteeStore.contacts`) — clause 3(3) names the secretary as the
referral point, and CR-013 built that seam for exactly this consumer.
Neither configured → log and skip; the queue still shows the
application.

### Approval — `AdminApplicationsResource`

All admin-only (`@RolesAllowed("admin")`):

- **`GET /api/admin/applications?status=`** → list, newest first, each
  row carrying the applicants, requested type, per-applicant
  **duplicate flags**, and for APPROVED rows the aging fields (below).
- **`GET /api/admin/applications/{id}`** → the same, one row.
- **`POST /api/admin/applications/{id}/approve`**
  `{membershipPeriodId, membershipTypeId, decisionDate,
  minuteReference?, householdName?}` → 200.
- **`POST /api/admin/applications/{id}/reject`**
  `{decisionDate, minuteReference?, reason?}` → 200.
- **`DELETE /api/admin/applications/{id}`** → 204, allowed only for
  RECEIVED/CONFIRMED (junk removal). A decided application is the
  society's record of a clause-3 decision — 409, never deletable.

**Approve** is one `jdbi.inTransaction` under the CR-010 discipline
(every failure a thrown `IllegalArgumentException`/`ConflictException`,
never an early-return `Response` — a half-materialized member must roll
back):

1. Guard: application exists and is CONFIRMED (else 409 — an
   unconfirmed or already-decided application is not approvable);
   `decisionDate` present and not in the future (it records a decision
   already taken).
2. The CR-010 sequence with the application's people:
   `PersonStore.create` per person (position-1 email attached as
   primary), `HouseholdStore.create` (name from `householdName`, else
   "familyName household" — the CR-010 default), `addPerson` for the
   second person with its relationship,
   `MembershipStore.createForHousehold` with the **admin-chosen**
   period and type (prefilled from the request, overridable — the
   requested type is advisory). Type people-bounds enforcement is
   reused from CR-010 (extract the shared helper if the duplication
   annoys; decide at implementation). `insertMembershipPerson` gives
   MEMBER rows their statutory/voting flags uniformly — the
   voting-rights rule holds here for free.
3. Stamp the membership's `application_date = submitted_at::date` and
   `approved_date = decisionDate` (a targeted UPDATE in the same
   transaction). `recompute` already preserves an existing
   `approved_date` (`COALESCE`) — payment later flips status to ACTIVE
   without touching the committee's date.
4. Insert the postal address, when given, as the household's POSTAL
   preferred `household_address` row — making this CR the **first
   writer** of that V1 table (the CR-015 first-consumer pattern); the
   mailing-label export and the CR-019 register pick it up with no
   change.
5. Mint the CR-004 pay link (`RenewalTokenStore.mint` — expiry is
   `GREATEST(end_date+1, now()+30d)`, which always covers the 28-day
   window; lost-link covers the rest) and write the application row:
   APPROVED, decision fields, `created_household_id`/
   `created_membership_id`.
6. After commit (the CR-012 webhook pattern — compose from committed
   state): send the **approval notice** to the confirmed position-1
   address — the clause 3(5)(a) deliverable — carrying the pay link
   and the 28-day wording. A zero-due approval (admin overrode to a $0
   type) skips the link and says the membership is active.

**Reject** is the same guard + decision-field write (REJECTED), then
the rejection notice — a neutral template; `reason` is stored for the
minute-book, never emailed (clause 3(5)(a) requires notice of the
decision, not grounds, and a volunteer society should not be drafting
adverse reasoning in a mail template). No refund handling: no money is
ever collected at application, so clause 3(5)'s refund duty is moot by
construction.

Both approve and reject refuse 503 when `Mail.enabled()` is false —
the notice is a statutory deliverable, so the decision is not recorded
without the means to give it (and CR-014 makes mail effectively
always-on in production).

**Duplicate flags** (soft guard, the CR-013 warnings posture — inform,
never block): per applicant, a case-insensitive email match against
`email_address` and a `lower(given_name)+lower(family_name)` match
against `person` (the `ImportService.findExisting` recipe), returned as
`matches: [{personId, name, householdId, hasCurrentMembership}]`. The
screen renders them loudly. A lapsed member re-applying is really a
renewal: the admin's remedy is the existing Renewals surface (mint a
pay link on the rollover membership) and delete/reject the application
— approve always creates **new** rows in v1 (see Rejected
alternatives).

### The 28-day aging view

No new mechanism: an APPROVED application joins its
`created_membership_id`; the list returns `paid` (status ACTIVE) and
`daysSinceDecision`, and the queue badges APPROVED-unpaid rows past 28
days. What happens then is the committee's call (clause 3(5)(b) — the
application can be treated as lapsed): the admin acts through existing
surfaces (cease the membership, or leave it), the soft-guard posture
again. No auto-withdrawal, no scheduler.

### UI — `admin/applications.html`

A queue page (nav entry added across the admin pages, the CR-019
precedent): status tabs defaulting to CONFIRMED, row detail with
applicants + duplicate flags, Approve… and Reject… dialogs (period
select defaulting to the current period, type select from cached
prices prefilled with the requested type, decision date + minute
reference fields; server 400/409 surfaced verbatim — the CR-018
dialog convention), Delete for junk, the APPROVED tab showing
paid/aging badges, and the settings card (alert mailbox, form
on/off). Guest side, `web/apply.html` as above.

### Sequencing

Independent of everything in flight. Ships dark: deploy any time, the
form answers 503 until `formEnabled` is set — which waits for the
committee's clause-3 minute.

### Rejected alternatives

- **Register-first: create APPLIED membership rows on submission** —
  puts junk in a never-delete statutory register and makes spam an
  admin problem; the staging table keeps the register meaning "the
  committee let this in". The V1 `APPLIED` enum value stays reserved.
- **Per-IP rate limiting** (the 2026-07-18 leaning) — the
  RemoteIpValve makes the address trustworthy, but CGNAT/shared-NBN
  makes it a false-positive machine at exactly this demographic, there
  is no per-IP infrastructure anywhere in the codebase, and the global
  backstop catches the same floods without it. `submitted_ip` is still
  recorded for forensics.
- **CAPTCHA / external anti-spam** — an external dependency and a
  volunteer-hostile UI for a form that expects single-digit monthly
  volume; the confirmation round trip is the real gate.
- **Approve-into-existing-household** (the lapsed-member re-apply) —
  a second materialization path with person-mapping UI for a case the
  Renewals surface already handles better; the duplicate flags route
  the admin there instead. Revisit if the flags prove insufficient.
- **Emailing the rejection reason** — the clause requires notice of
  the decision only; canned neutral wording avoids the society
  publishing adverse reasoning. The reason field is internal.
- **Auto-cease at 28 days** — clause 3(5)(b) makes lapse permissive,
  not automatic; committee authority, admin acts manually (the CR-013
  eligibility posture).
- **Reusing CR-005 segment machinery for the notices** — these are
  one-off transactional sends; `Mail.sendAsync` is the CR-012-settled
  pattern.
- **An invite/confirm re-send endpoint** — re-submitting the form is
  the remedy for a lost confirmation email; an endpoint would be a
  fresh enumeration surface.

## Config

One migration (V10, the two staging tables). One new `app_setting` key
(`application_settings`). No new env, no realm change, no new Keycloak
role. New static pages `web/apply.html` (+ `apply.js`) and
`admin/applications.html`; nav edits across admin pages.

## Verification plan

Matrix rows (extend `server/verify-matrix.sh`; fixtures use `$$`-unique
names/emails — self-cleaning, and the per-email cooldown never trips
across runs):

| # | case | expect |
|---|---|---|
| CR7-01 | guest/user/noaud GET /api/admin/applications | 403/403/401 |
| CR7-02 | guest/user/noaud PUT /api/admin/application-settings | 403/403/401 |
| CR7-03 | admin GET application-settings before any save | 200, formEnabled false, alertMailbox null |
| CR7-04 | guest GET /api/apply/options while disabled | 200, open=false |
| CR7-05 | guest POST /api/apply while disabled | 503 |
| CR7-06 | admin PUT application-settings {alertMailbox: mailpit-visible addr, formEnabled: true}; GET echoes | 200/200 round trip |
| CR7-07 | guest GET options | 200; SINGLE/HOUSEHOLD present with prices; no zero-priced (LIFE) type |
| CR7-08 | guest POST /api/apply valid single applicant | 202; psql: one RECEIVED row + one person row; Mailpit: confirmation mail to applicant |
| CR7-09 | honeypot field filled | 202; psql: row count unchanged |
| CR7-10 | validation: missing email / missing given name / unknown type / zero-priced type | 400 each |
| CR7-11 | immediate resubmit, same email | 429 (cooldown) |
| CR7-12 | POST /api/apply/confirm with token extracted from the Mailpit message | 200; psql: CONFIRMED + confirmed_at set; Mailpit: alert mail to alertMailbox |
| CR7-13 | confirm again (same token) | 200; still exactly one alert mail |
| CR7-14 | confirm with garbage token | 404 |
| CR7-15 | confirm with psql-expired token (backdate confirm_expires_at) | 404, indistinguishable from CR7-14 |
| CR7-16 | admin approve an unconfirmed (RECEIVED) application | 409 |
| CR7-17 | admin approve confirmed app {periodId, typeId, decisionDate, minuteReference} | 200 {householdId, membershipId, personIds}; psql: membership PENDING_PAYMENT, application_date = submission date, approved_date = decisionDate |
| CR7-18 | CR7-17 side effects | psql: membership_person MEMBER flags true; household_address POSTAL row present when address given; Mailpit: approval notice containing /web/pay.html?t= |
| CR7-19 | pay link extracted from the approval notice | guest GET /api/pay/{t} 200, amount = type price |
| CR7-20 | approve again / reject after approve | 409 |
| CR7-21 | household application, second person MEMBER | approve → both persons voting=true (psql) |
| CR7-22 | household application, second person PARTNER | approve → partner voting=false; partner doesn't count against maximumPeople |
| CR7-23 | reject a confirmed application {decisionDate} | 200; psql: REJECTED, register person-count unchanged; Mailpit: rejection notice |
| CR7-24 | DELETE a RECEIVED junk row / DELETE an APPROVED row | 204 / 409 |
| CR7-25 | duplicate flag: application using an existing fixture person's email | admin GET detail shows matches[].personId |
| CR7-26 | aging: psql-backdate an APPROVED app's decision_date by 30 days | list shows daysSinceDecision ≥ 30, paid=false |
| CR7-27 | approve decisionDate in the future | 400 |
| CR7-28 | static pages: GET web/apply.html, admin/applications.html | 200 each (the static-pages matrix block) |

Mail-disabled 503s (apply/approve/reject when `Mail.resolve()` is NONE)
are asserted by code inspection — the dev stack always has the ENV
fallback configured, and CR-014 proved the NONE gate generically.

Browser walkthrough (Playwright, the CR recipe): closed-form message;
settings card save; public form submit incl. second-person and
honeypot-invisible check; confirmation landing; admin queue → detail →
Approve dialog round trip; Reject dialog; aging badge visible on a
backdated fixture.

## Results

(after implementation)

## Follow-ups / amendments

- **2026-07-23 — committee answers received** (relayed from the
  committee-questions handout, ydhs/):
  - Entrance fee: set to zero; system need not accommodate it
    (unblocks this CR — see Outstanding issues).
  - Approved-form resolution (clause 3): committee will minute the
    website form as the approved application form before go-live;
    pending. (Now the `formEnabled` switch — deploy dark, enable when
    minuted.)
  - New-application alert address: `membership@yasshistory.org.au`
    (shared mailbox), must be configurable on an admin page.
  - Context from the same round, relevant here: card-processing fees
    stay absorbed by the society; the system continues to issue its own
    receipts (Stripe's emailed receipts stay off); free-entry donation
    amount on the pay page — committee to advise; prices ($45/$65,
    journal +$10), membership year (1 Sep–31 Aug) and renewal opening
    (1 Jul) all confirmed unchanged.
- **Deferred to a later CR / revisit-if-it-bites:** bulk purge of
  stale RECEIVED rows (per-row delete only in v1 — no scheduler
  exists and spam volume is unproven); approve-into-existing-household
  (see Rejected alternatives); auto-provisioning a Keycloak account at
  approval (stays with CR-006's manual button).
