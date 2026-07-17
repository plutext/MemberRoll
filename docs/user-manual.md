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

Everything below is under the admin panel's three areas: **Users**,
**Register** (people, households, import), and **Renewals** (periods,
memberships, payments, exports).

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
member name, and filter by **Status**:

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
  or `OTHER` — card payments arrive automatically once online payment is
  live and are never keyed in here), and an optional bank **reference**.
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

To add a returning or brand-new member outside of rollover, open the
household (**Register → Households → Members**) and use **New membership**:
pick the period and the membership type, then **Create membership**. It's
created Unpaid (or $0 Paid for a life/honorary type), with the household's
current members copied onto it. Record their payment from the members table
as usual. If the join date is past the period's late-joining cutoff, the app
notes that so you can consider signing them up for the following year
instead.
