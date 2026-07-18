# MemberRoll roadmap

MemberRoll manages membership renewals for a small society. The first
production instance is for the [Yass & District Historical
Society](https://yasshistory.org.au/membership/) (~100 members); the app
is open source so other societies can host their own instance (500–1000
members should be comfortable). This document records the scope
decisions and the planned change-request sequence; individual CRs in
`change-requests/` carry the detailed designs.

## Context (Yass society, as at July 2026)

- Fees: Single $45, Household $65, printed *Boongaroon* journal +$10
  (2025/26 prices — confirm current before go-live).
- Members receive the monthly *Yandoo* newsletter and annual
  *Boongaroon* journal (print-by-post or PDF-by-email, member's choice).
- Payment today is bank transfer with the member's name in the
  reference line, reconciled by hand against a spreadsheet.
- New members joining on or after 1 July are financial for the
  following membership year (exact period boundaries to be confirmed
  with the society — see open questions).

## Decisions

| Date | Decision |
|---|---|
| 2026-07-17 | **Single-tenant.** One society per instance; avoid hardcoding "Yass" outside configuration, but no tenant model. |
| 2026-07-17 | **Data model** per [membership_management_database_schema.md](membership_management_database_schema.md): the household is the billing unit, the person is the member unit; history is preserved by inserting new rows (new Membership per period), never by overwriting. |
| 2026-07-17 | **Postgres**, as a second database in the container the prod stack already runs for Keycloak. SQLite/MariaDB are a documented porting door, not supported engines — keep SQL boring (integer cents, UTC timestamps, no Postgres-isms without cause) and all access behind store classes so a port stays feasible; `verify-matrix.sh` is the portability contract. |
| 2026-07-17 | **JDBI 3 + hand-written SQL + Flyway**, no ORM. The schema is insert-only and the valuable queries are reporting-shaped joins — the opposite of Hibernate's load-mutate-flush model — and volunteer maintainers can read SQL. Flyway migrations are the single source of schema truth. |
| 2026-07-17 | **Pay without login = tokenized magic links.** Renewal emails carry a long random single-membership token; the public page it opens shows the amount due and starts a Stripe Checkout session. Stripe-hosted payment page (card data never touches the server, PCI SAQ A); webhook records the Payment and activates the membership. Login (Keycloak) stays for admins and opt-in member self-serve, never as the payment gate. Unauthenticated visitors are never shown membership status directly — the "lost my link" page emails the link to a matching address instead. |
| 2026-07-17 | **Manual payments are first-class**: bank transfer (today's main channel), cash, and cheque are recorded by an admin with an audit trail (who recorded, when). |
| 2026-07-17 | ~~**Voting rights**: per the society, both adults in a Household membership are statutory voting members.~~ Superseded 2026-07-18 below — this was asserted without a recorded rationale. |
| 2026-07-17 | **The 1 July rule is MembershipPeriod configuration** (`late_joining_cutoff`), not code. |
| 2026-07-17 | **Email is synchronous SMTP relay**, per-recipient sends with merge fields and a send log. No queue at this scale (~100–1000 recipients). |
| 2026-07-18 | **Communication preferences default to EMAIL.** Only a handful of members receive *Yandoo*/*Boongaroon* by post, so no preference row = email; the exceptions are set manually to POST through an admin preferences UI (CR-005 — the `communication_preference` table shipped empty in CR-001 and nothing manages it before then). No re-import of the member list for this: the post members are entered by hand. |
| 2026-07-18 | **Segment email addresses MEMBER people only** (CR-005): renewal/newsletter emails go to a household's current `relationship_type` MEMBER people (deduped when they share an address), never PARTNER/DEPENDANT/OTHER — the formal member is who the society corresponds with, mirroring the voting-rights correction below. |
| 2026-07-18 | **Voting rights, corrected**: only the household member recorded with `relationship_type` MEMBER is a formal, statutory voting member. PARTNER/DEPENDANT/OTHER receive membership benefits (covered by the membership, counted for the household's occupancy) but do not vote, hold statutory-member status, or stand for committee. Also settles the CR-010 question of whether a PARTNER/DEPENDANT/OTHER second person counts against a membership type's `maximum_people`: it does not — only a second MEMBER does, since `maximum_people` caps formal members, not household occupants. |
| 2026-07-18 | **Keycloak↔Person linking is provision-time, register-push** (CR-006, superseding the match-at-login working assumption): an idempotent admin-triggered step creates (or adopts, verified-email only) a Keycloak account per qualifying member email and writes `person.keycloak_subject` at that moment — no match-at-login heuristic, no confirmation queue. Imported addresses count as verified; self-registered accounts must verify (realm Verify Email on). One email address may be in use in at most one household; within a household the shared address's account goes to the CR-005-attributed person (primary contact, else lower person id). **Self-serve is MEMBER-only** (the correspondence principle above). Rollout is **silent + forgot-password** — no invite blast; first login goes through Keycloak's password reset. Self-serve endpoints authorize on the link, not the self-claimed `member` role; Keycloak account lifecycle is not used for authorization (per-request DB checks are). Principles recorded in full in [CR-006](change-requests/006-member-self-serve.md). |

## In scope

1. Core member register (people, households, memberships, periods,
   types, payments, allocations, contact details, communication
   preferences).
2. CSV import of the society's existing member list, with a
   preview/dedup step.
3. Admin panel: financial status per period with filters
   (paid / unpaid / lapsed / applied), search, manual payment entry,
   people/household editing, CSV exports (AGM register, mailing labels).
4. Period rollover: open the new membership year, generating
   PENDING_PAYMENT memberships for currently-active households.
5. Stripe Checkout with webhook, magic-link pay pages, journal add-on
   and optional donation line (PaymentAllocation), receipt email.
6. Segment email via SMTP relay: templates, merge fields (name, amount,
   magic link), send log, CommunicationPreference honoured, "these N
   members have no email" surfaced rather than silently skipped.
7. Member self-serve: Keycloak-linked "my membership" page with pay-now.
8. Public new-member application form → APPLIED → admin approval.
9. Backups covering the database (financial records).

## Out of scope (v1)

- Multi-tenancy.
- Recurring/auto-renew Stripe subscriptions (annual one-off payments
  match how the society operates; subscriptions add card-update,
  dunning and cancellation complexity).
- Refunds through the app (use the Stripe dashboard; record manually).
- Bank-feed / statement-CSV reconciliation (admin marks transfers
  manually).
- Events, volunteering, shop sales, donation (DGR) receipting,
  accounting exports (Xero), SMS.
- Member self-service editing of contact details (view + pay only in
  v1).
- WordPress integration beyond a link from the society website.
- Bounce handling beyond the relay's own reporting.

## Change-request sequence

Status vocabulary (the change-request lifecycle, `change-requests/README.md`):
**Planned** (no CR doc yet) → **Proposed** (doc written) → **Implemented**
(built, scripted matrix green) → **Verified** (browser walkthrough / real
data too) → **Committed**.

| CR | Title | Status | Delivers |
|---|---|---|---|
| 001 | Membership register data layer | Verified · committed | Postgres + Flyway + JDBI, core schema, admin people/household CRUD API, minimal admin register UI |
| 002 | Import | Verified · committed | CSV import with preview/dedup; synthetic dev fixture shaped like the real list |
| 003 | Renewals and manual payments | Verified · committed | Period rollover, cash/cheque/transfer payment entry, financial-status filters, CSV exports |
| 004 | Stripe | Verified · committed | Magic-link pay page, Checkout, webhook, receipt email (brings minimal SMTP config), journal add-on + donation line |
| 005 | Segment email | Verified · committed | Templates, merge fields, segment sends with embedded magic links, send log; communication preferences: admin preferences UI (default EMAIL, manual POST exceptions) and sends honouring it |
| 006 | Member self-serve | Implemented (2026-07-18, verified) | Keycloak-linked "my membership" page, pay from there; register-push account provisioning; retired the NotesResource placeholder |
| 007 | Public application form | Planned (pre-proposal notes 2026-07-18) | New-member APPLIED workflow with committee approval — constitutional constraints (clause 3: committee decision, 28-day payment window, no money at application) recorded in the CR doc ahead of the proposal; entrance-fee question deferred |
| 008 | Production hardening | Planned | Prod DB provisioning, Stripe live keys, SPF/DKIM/from-address, backup coverage, deploy docs |
| 009 | UI polish (out-of-band) | Verified · committed | Pico CSS baseline across all static pages, dialog-based forms, person picker, status badges — orthogonal to the sequence. Implementation order decided 2026-07-18: 009 lands before 004, so the public pay page starts on the new baseline |
| 010 | Admin "new member" page (out-of-band) | Verified · committed | One-flow walk-in signup: person → household (person as primary contact) → membership type → second-person dialog for HOUSEHOLD; composite atomic endpoint; first consumer of membership_type min/max people. After 009; independent of 004 |
| 011 | Constitutional register compliance (out-of-band) | Proposed | Gaps from the 2026-07-18 constitution review, one stage each: register-of-members export (clause 4), clause 4(5) suppression flag, under-18 AGM exclusion (clause 34), documented manual suspension procedure (clause 7). Independent of 007/008 |
| 012 | Payment receipts (out-of-band) | Verified · committed | On-demand receipt per payment — email or print — covering CR-003 manual payments (clause 38(3)(b): receipt if requested); one shared renderer also backing the CR-004 Stripe receipt. No schema/realm change; independent of 007/008 |

Ordering principle: admin value first — after CR-003 the app already
replaces the spreadsheet-and-bank-statement process with zero
member-facing features — then payment collection, then the outbound
comms that carry the payment links. Emails could precede Stripe (with
bank-transfer instructions in the body), but a renewal email is far
more effective carrying a pay-now link.

## Open questions

- Exact membership-year boundaries and renewal-open date (the site
  implies a 1 July rule and lists "2025/2026" fees; confirm the period
  start/end and the current year's prices with the society).
  *Partially answered by the constitution (read 2026-07-18): the
  financial year is 1 September – 31 August (clauses 5(2)(a), 44), and
  the "1 July rule" is clause 5(2)(c)'s discretionary last-2-months
  rule — prices still to confirm.*
- Whether the society passes the Stripe fee on to the payer.
- **Entrance fee — DEFERRED (2026-07-18).** Constitution clause 5(1)
  prescribes a $20 entrance fee for approved applicants ("or another
  amount determined by the committee") on top of the annual
  subscription. Unknown whether one currently applies; if it does,
  CR-007's approval flow must bill entrance + subscription and the
  payment model needs a shape for it. Deferred until the society
  confirms — recorded as an outstanding issue in
  [CR-007](change-requests/007-public-application-form.md).

Answered 2026-07-18: the Keycloak↔Person linking rule and the fate of
the self-claimed `member` role — see the decision above and CR-006
(linking is provision-time; the claimed role stays as informal
self-description, with self-serve gated on the link).
