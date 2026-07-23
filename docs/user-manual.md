# MemberRoll user manual

For the society's administrators and treasurer. It covers the admin panel
— the register, member import, and running a membership year (renewals,
payments, exports). It describes what the app does today; features arrive
per the [roadmap](ROADMAP.md).

## Signing in

Open the admin panel at `/server/admin/` (on the society's server; in dev,
`http://localhost:18080/server/admin/`). You log in through the society's
Keycloak sign-in page. The panel is for **admins** — if your account
doesn't have the admin role you're sent to the member page instead. Your
username and roles show at the top; **Log out** is beside them.

Everything below is under the admin panel's areas: **Users**,
**Register** (people, households, import), **New member** (a walk-in or
paper-form signup in one step), and **Renewals** (periods, memberships,
payments, exports).

## The register

### People

Each person is a record of an individual — a member, a partner, a past
member. People are **never deleted**: the register is the historical record,
so someone who doesn't renew stays on file (departures are recorded as
dates, not by removing the row).

- **Search** by name or email; **New person** to add one.
- **Edit** to change details. Emails go one per line (the first is the
  primary); phones one per line, optionally followed by `MOBILE`, `HOME`
  or `WORK`.

### Households

A household is the **billing unit** — a single membership uses a one-person
household; a couple or family shares one. Each household has one **primary
contact**.

- **New household** needs an existing person as the primary contact.
- **Members** opens a household: add people (with a relationship —
  `MEMBER`, `PARTNER`, `DEPENDANT`, `OTHER`), or **Remove** them. Removing
  records a *leaving date* rather than deleting the row, so history is kept
  and a person can later rejoin.
- The primary contact can't be removed — reassign it first (edit the
  household and pick another current member).
- From an open household you can also start a **New membership** for a
  period (see [Signing up a household mid-year](#signing-up-a-household-mid-year)).

### New member (walk-ins and paper forms)

**New member**, its own page, is the fast path for the commonest data-entry
job — someone joins today with a form or in person — without hopping
between People, Households and Renewals in the right order:

1. Enter the person's details. If a similar name or email already exists
   you'll see a "possible existing match" note — advisory only, it doesn't
   block you.
2. Pick the period and membership type; the price shows. Give the
   household a name, or leave it as the suggested "*Family name*
   household".
3. For a type that covers more than one person (e.g. Household), a dialog
   asks for the second person (name, and their relationship — Partner by
   default). You can skip it — a household with only one person entered
   still gets created, with a note reminding you to add the partner later
   from the household's Members screen; they won't be added to *this*
   membership retroactively, only to any future one.
4. **Create member** makes the person, the household (with them as
   primary contact) and the membership together, or not at all — a
   mistake partway through creates nothing. The result screen links to
   the household and the membership (where you can record a payment, or
   copy a pay link if Stripe is configured).

### Importing members from a spreadsheet

Under **Register → Import members (CSV)**:

1. Shape your spreadsheet to the columns in `docs/import-template.csv` and
   export it as **CSV UTF-8** (plain "CSV" from Excel mangles accented
   names).
2. Pick the **Target period** from the dropdown — leave it on
   *(current period)* to use the period covering today. If no period exists
   yet, the app says so here: create one under **Renewals → New period**
   first (a fresh install ships with the current year's period already set
   up). People and households still import without a period; only rows that
   carry a `membershipType` need one.
3. **Preview** is a dry run: it reports what would be created, warns about
   duplicates, and writes nothing. **Apply** becomes available only once a
   preview comes back with no errors, and writes everything in one go.

Rows sharing a `household` label become one household. People already in the
register (matched by email, or given + family name) are skipped, so
re-running a corrected file converges instead of duplicating.

## Renewals

The **Renewals** area runs a membership year end to end: open a period with
its prices, roll the prior year's paid members forward, record the cash and
transfers as they arrive, and export the AGM and mailing lists.

At the top, the **Period** dropdown chooses which year you're working in.
Beneath it a summary shows that period's dates and prices; below the members
table, a totals line shows the count in each status and how much has been
collected against how much is due.

### Creating a new period

Press **New period**. The form **pre-populates from the period currently
selected** in the dropdown, because the common case is "next year, same
fees":

- **Name** is left blank — type the new one (e.g. `2026-2027`).
- **Start date, End date, Renewal opens, Late-joining cutoff** are each the
  selected period's dates **wound forward by one year** (so `2025-09-01`
  becomes `2026-09-01`). A date the source period didn't set stays blank.
- **Prices** are carried over unchanged, one box per membership type,
  shown in dollars.

Adjust whatever actually changed — a fee rise, a shifted cutoff — then
**Create period**. A price is required for **every** membership type (so a
later rollover can never fail for want of one), and a duplicate period name
is refused.

### Rolling last year's members forward

Rollover creates a membership in the selected (target) period for **every
household that had an ACTIVE (paid) membership in the prior period**, at the
target period's prices.

1. Select the new period, then **Preview** — it reports the source period it
   found, how many memberships it *would* create, and which households it
   skips. **It writes nothing.**
2. **Apply rollover** performs it. New memberships start **Unpaid**
   (awaiting payment), except life/honorary memberships, which come across
   as **$0 Paid** automatically.

Households that already have a membership in the target period (an early
renewal), and households with no current members, are **skipped** and
listed. Rollover is safe to run again — a second run creates nothing and
skips everyone. Members who *didn't* pay last year are not rolled over; sign
them up manually if they return.

### Who has paid — the members table

The members table lists the period's memberships. **Search** by household or
member name, filter by **Type** (e.g. show only `LIFE` or only `SINGLE`
memberships), and filter by **Status**:

| Filter | Meaning |
|---|---|
| Unpaid | awaiting payment |
| Paid | fully paid / active |
| Lapsed | unpaid past the grace period |
| Applied | pending admin approval (a future feature) |
| Ceased | resigned, deceased, or otherwise ended |

Each row shows the amount **Due** and the amount **Paid** (paid is always
shown, so a partial payment is visible). **Manage** opens the membership.

### Recording a payment

Open a membership with **Manage**, then **Record payment**. The form is
pre-filled with the outstanding **balance** as the membership amount:

- Set the **Received date**, **Method** (`BANK_TRANSFER`, `CASH`, `CHEQUE`
  or `OTHER` — card payments arrive automatically via the online pay page
  and are never keyed in here; `STRIPE` in this form is only for recording
  a refund, see below), and an optional bank **reference**.
- To split a single transfer — e.g. "$75 = $65 membership + $10 donation" —
  use **Add allocation line** for the extra part (donation or journal). The
  allocation lines must add up to the total paid.

A membership flips to **Paid** automatically once the payments allocated to
it cover the amount due (only membership allocations count toward the fee —
donations and journal add-ons don't). Overpayment is allowed and flagged as
a note.

### Corrections — Reverse, never edit

Payments are never edited or deleted. To fix a mistake, use the **Reverse**
button beside the payment: it records an equal-and-opposite entry (and notes
which payment it reverses). Both entries stay in the record with who entered
them and when, and the membership's status re-derives automatically — so a
reversed payment can drop a membership back to **Unpaid**.

### Life membership — changing a membership's type

A **life member** pays no annual fee. There is nothing special to do each
year: a life membership is simply one whose type is `LIFE` (price $0), so it
shows **Paid** immediately, rolls forward automatically each period at $0,
never appears in unpaid lists or renewal-chasing email segments — and the
member still votes and appears on the AGM register like any other.

To grant (or correct) it, open the membership with **Manage** and use the
**Type** row: pick the new type — each option shows its price for that
period — and press **Change type**. The amount due re-snapshots to the new
type's price and the status follows: setting `LIFE` on an unpaid membership
makes it **Paid** at $0 due; setting a paid type back on a life membership
returns the real price and the membership shows **Unpaid** until paid.

Two guardrails:

- **Money first.** The type cannot change while payments are allocated to
  the membership — repricing under money is how registers drift. If the
  membership was paid (or part-paid), **Reverse** the payment(s) in the same
  dialog first; once the net is back to zero the change is allowed. (This is
  also the recovery for "imported as an ordinary paid member but is really a
  life member": reverse the recorded payment, then change the type to
  `LIFE`.)
- A **Ceased** membership's type cannot change — the record is closed.

Use the members table's **Type** filter (`LIFE`) to verify the result: the
life households, all **Paid**, due $0.00.

### Online card payment — pay links

Members can pay by card without any login: you send them a **pay link**
(a long single-membership web address), the page it opens shows what's
due, and payment happens on Stripe's hosted page — card details never
touch this app.

- **Getting a link**: open a membership with **Manage** and click
  **Copy pay link**. Paste it into an email to the member. Each click
  makes a fresh link; links you sent earlier keep working until the
  membership year ends. (The renewal-email feature will later send these
  automatically.)
- **What the member sees**: household, year, amount due and paid. If a
  **journal add-on price** is set on the period (the field next to the
  period selector), the page offers the journal as a tick-box; there's
  also an optional donation amount. After paying they land back on the
  page, which confirms "you are financial for …", and they receive a
  receipt email. The membership flips to **Paid** here automatically —
  nothing to key in.
- **Lost links**: someone whose link expired can enter their email
  address on the pay page; if it matches a member, the link is emailed
  to them. The page never reveals membership details to visitors without
  a valid link.
- **Refunds**: make the refund itself in the Stripe dashboard, then
  record it here as a payment with method `STRIPE` and a **negative**
  amount. (Positive `STRIPE` entries are refused — real card payments
  only ever arrive via Stripe's confirmation.)
- If a member pays online after you've already keyed in their bank
  transfer, both are recorded and the membership shows overpaid — refund
  one of them as above.

### Lapsing and ceasing

Both **Lapse** and **Cease** end a membership, but they mean different things
and behave differently. Rule of thumb: **Lapse** for the ordinary "hasn't
renewed this year" case (soft and recoverable); **Cease** when someone is
genuinely off the books (hard and permanent).

**Lapse** — *didn't renew (yet).* Offered only on an **Unpaid** membership.
It's a governance statement that an unpaid member has dropped off the roll,
usually after the grace period the committee has agreed. It's **reversible**:
**Undo lapse** returns it to Unpaid, and a later payment **reactivates** it
to Paid automatically — so lapsing burns no bridges. No reason or date is
asked for.

**Cease…** — *the membership has ended, for a reason.* Offered on **any**
membership that isn't already ceased. You give a **reason** (`RESIGNED`,
`DECEASED`, `OTHER`) and a **date**. It's **terminal**: there's no un-cease,
and later payment activity leaves a ceased membership untouched (a stray
payment won't accidentally revive it).

|                     | Lapse                                   | Cease                                  |
|---------------------|-----------------------------------------|----------------------------------------|
| Use when            | they just haven't paid                  | they've resigned / died / are leaving  |
| Allowed from        | Unpaid only                             | any status                             |
| Needs reason + date | no                                      | yes                                    |
| Reversible          | yes (undo, or a late payment revives it)| no — deliberate, permanent             |
| Bulk action         | yes (**Lapse all unpaid**)              | no, one at a time                      |

**Lapse all unpaid** bulk-lapses every unpaid membership in the selected
period in one action (with a confirm). It's a deliberate once-a-year step,
never automatic.

A note on deaths: a member *dying* is not, by itself, a membership status.
For a **household** membership, one person's death is recorded against that
person — it doesn't end the household's membership. You'd only **Cease** with
reason `DECEASED` when it's a single-person household whose sole member has
died.

### Exports

The **export** buttons download a CSV for the selected period:

- **AGM register** — one row per voting member of a paid membership (family
  name, given name, household, type). Non-voting people (e.g. dependants)
  and non-active households are excluded.
- **Mailing labels** — one row per household with a paid membership, with
  its preferred postal address. Households with no postal address are still
  included, with the address columns blank (so nobody is silently dropped).
- **Financial** — one row per membership in the period (household, contact,
  type, status, due, paid).

### Signing up a household mid-year

For a **brand-new** member, use **New member** (above) instead — it
creates the person, household and membership together. This section is
for a household that **already exists** (a returning member outside of
rollover, or one already in the register): open the household
(**Register → Households → Members**) and use **New membership**:
pick the period and the membership type, then **Create membership**. It's
created Unpaid (or $0 Paid for a life/honorary type), with the household's
current members copied onto it. Record their payment from the members table
as usual. If the join date is past the period's late-joining cutoff, the app
notes that so you can consider signing them up for the following year
instead.

## Membership applications

The **Applications** page manages applications from the public form at
`web/apply.html` (constitution clause 3). The flow: someone applies on
the website → they confirm by clicking a link emailed to them (nothing
reaches the queue without that — unconfirmed junk stays under the
*Unconfirmed* filter) → the committee considers it at a meeting → you
**record** the decision here. No payment is taken at application;
membership starts when the approved applicant pays.

- **Form settings** (bottom of the page): the form ships **switched
  off**. Turn it on only once the committee has minuted the website form
  as the society's approved application form and permitted electronic
  lodgement — flipping the switch is go-live. The **alert mailbox** gets
  an email whenever an application is confirmed; leave it blank and the
  current secretary (from the Committee page) is notified instead. Both
  need mail configured (Mail settings) — without it the form stays
  closed and decisions can't be recorded, because the applicant's
  written notice is a constitutional requirement.
- **Deciding**: open the application (**View…**). A warning flags anyone
  who may already be on the register — a lapsed member re-applying is
  really a *renewal*: prefer minting them a pay link from the Renewals
  page and deleting the application. **Approve…** asks for the
  membership year, type (prefilled with what they asked for), the
  committee's **decision date** (the meeting date — it's a record, not a
  button that decides) and an optional minute reference. Approving
  creates the person(s), household and membership in one step and emails
  the applicant an approval notice with a pay link; they have 28 days to
  pay. **Reject…** records the decision and sends a neutral notice — the
  reason field is for the society's records and is never emailed.
- **A second person on the application** is either *also applying*
  (a voting member — needs a household type) or *a partner covered by
  the membership* (never votes). This mirrors the register's rule.
- **Afterwards**: approved applications show **paid** once the payment
  lands, and an **unpaid Nd** badge past 28 days — what to do then
  (chase, extend, or treat as lapsed) is the committee's call; the app
  never auto-cancels. **Delete** exists for junk only; a decided
  application is the society's record and can't be deleted.

## Reports

The **Reports** page holds the cross-cutting exports — questions that span
years or cut across the register, rather than belonging to one period's
work. Each downloads a CSV to open in a spreadsheet.

- **Register of members** — the constitution's clause 4 register: every
  person who holds or has ever held a formal membership place, with full
  name, one address (preferred postal address, else email), the date they
  became a member and, for former members, the date they ceased. Unlike the
  AGM register it *includes* lapsed and ceased members — that is the point.
  Two derivations to know: "date became a member" is the start of the
  person's earliest membership year, so for members brought in by the
  spreadsheet import it is the earliest *imported* year, not their true
  historical join date; "date ceased" is the recorded cease date where one
  exists, otherwise the end of their last membership year (or today, if
  they lapsed inside a year still running).
- **People without a current membership** — current household people (not
  left, not deceased) holding no membership place in the chosen year, with
  the last year they did hold one. This is the person-by-person residue the
  rollover can leave behind — run it after rollover each year.
- **Unrenewed households** — households financial in one year whose state
  in a later year is anything else (unpaid, lapsed, ceased, or nothing at
  all). The ring-around chase list, with the primary contact's email and
  phone. For chasing by *email*, the Email page's segments are the tool;
  this report is for the phone tree and the committee meeting.
- **Donations** — payments carrying a donation part, over a received-date
  range (blank dates = all time), with a trailing total. Reversals appear
  as negative rows, so the total always matches the ledger.

The per-year exports (AGM register, mailing labels, financial) stay on the
Renewals page — see [Exports](#exports) above — and the treasurer's
reconciliation exports are there too.

## Mail settings and the testing sandbox

**Mail settings** (in the admin menu) holds the SMTP relay the app sends
through — receipts, segment emails, membership cards, application
notices. Settings saved here take effect on the very next message (no
restart needed); if nothing is saved, the server's environment
configuration is used. The **Send a test email** button tries the values
in the form without saving them and shows the relay's own reply verbatim
— that is the tool for debugging a provider setting (there is a
one-click Microsoft 365 preset, and notes on its requirements, on the
page itself).

### Sandbox — redirect all outgoing mail while testing

When testing with real member data (a demo, a dress rehearsal), set
**Sandbox: redirect all outgoing mail to** to a tester's address and
Save. From the next message on, **every** email the app would send — to
anyone, including mail triggered by visitors on the public pages — is
delivered to that one address instead. Each redirected message shows who
it was really for: the subject starts `[SANDBOX for jo@example.com]` and
the first line of the body repeats it. Everything else (content,
attachments, the relay) is exactly the real thing, so what you are
testing is still being tested.

- While the sandbox is on, every admin page shows an orange **SANDBOX**
  banner in the header — if you can see the panel, you can see the mode.
- The Email area's send log still records the **real** recipients: the
  log records what was meant; the sandbox only changed where the
  messages were carried. Pay links minted by a send work normally — they
  simply arrive at the sandbox address.
- To go live again, clear the field and Save. The banner disappears and
  the next message goes to its real recipient.
- **Keycloak's sign-in emails are separate**: the Forgot Password email
  is sent by the login system's own mail configuration, not the app's —
  while testing, point that at the same sandbox mailbox too (a deploy
  runbook step), and back again afterwards.

## Member self-serve — "my membership"

Members can (optionally) log in and see their own household's membership
at `/server/web/` — status, amount due, past years — with a **Pay now**
button that opens the same pay page as an emailed pay link. It's view +
pay only: members can't edit their details online.

- **Giving members access — provisioning**: on **Users**, the
  **Self-serve provisioning** section creates a login for every current
  formal member who has an email address, and links it to their register
  record. Click **Preview** first (it changes nothing), read the report,
  then **Provision**. It's safe to run any time — after an import, after
  a walk-in, or just to be sure; people already provisioned simply show
  *Already linked*.
- **Reading the report**: *Create account* / *Adopt existing account*
  are the ones that will act. *Shared address* is normal for couples —
  one address gets one login, held by the household's primary contact.
  *Conflict: two households* means the same email address appears in two
  different households — fix the register data, then run again.
  *Skipped: unverified account* means someone self-registered with that
  address but never confirmed it — the app won't link an unconfirmed
  mailbox to a member's record.
- **Nothing is emailed.** Provisioning is silent. A member who wants to
  use the page goes to the sign-in screen and clicks **Forgot
  Password?** — the reset email (to their known address) lets them set a
  password and they're in. When the society wants to announce the
  feature, send a segment email (the Email area).
- **Members who sign themselves up** (the Register link) must confirm
  their email address, and until you provision/link them they see "we
  couldn't find a membership linked to this account — contact the
  society". There's deliberately no way for a visitor to look up
  membership records.
- **Unlinking**: in a person's record (Register → People → Edit), the
  **Self-serve account** section shows whether they're linked, with an
  **Unlink** button (use it if an email address was reassigned or linked
  to the wrong person). Their login keeps working but shows no
  membership; provisioning can re-link later. Members who leave are
  handled automatically — the page always checks the current register,
  so someone removed from a household simply stops seeing it.
