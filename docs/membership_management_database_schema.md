# Membership Management System Database Schema

## Recommendation

Use separate tables for:

- **People or contacts**
- **Households**
- **Memberships**
- **Membership periods**
- **Payments**

A person exists independently of any particular membership. A household groups people who share a membership arrangement. A membership records entitlement for a defined period, while payments record the financial transactions associated with joining or renewal.

The core design principle is:

> **The household is the billing and subscription unit; the person is the member and contact unit.**

## Core entities

### Person

Represents an individual independently of their current household or membership status.

```text
Person
  person_id
  title
  given_name
  family_name
  preferred_name
  date_of_birth          nullable
  deceased_date          nullable
  notes
```

A person record should not be deleted merely because the person does not renew. It may be required for historical membership records, donations, volunteering, event attendance, or future reactivation.

### Household

Represents a group of people who share a household membership arrangement.

```text
Household
  household_id
  household_name         nullable
  primary_contact_person_id
  status                 ACTIVE / CLOSED
```

Examples of `household_name` might include:

- `Smith household`
- `John and Mary Smith`
- `Brown family`

The household should not itself be treated as a person.

### HouseholdPerson

Associates people with a household and allows changes in household composition to be recorded over time.

```text
HouseholdPerson
  household_id
  person_id
  relationship_type      MEMBER / PARTNER / DEPENDANT / OTHER
  joined_household_date
  left_household_date
```

The primary contact is recorded once, as
`Household.primary_contact_person_id` — not duplicated as a flag here,
where the two copies could disagree.

This allows the system to represent situations such as:

- a couple joining together;
- an adult child leaving the household;
- one person becoming the primary contact;
- a person moving from one household to another;
- a household being closed while retaining its history.

## Membership structure

### MembershipType

Defines the categories of membership offered by the society.

```text
MembershipType
  membership_type_id
  name                   SINGLE / HOUSEHOLD / LIFE / HONORARY
  description
  minimum_people         nullable
  maximum_people         nullable
  active_from
  active_to
```

There is no price on the type itself; prices are set per membership
period (below), so fee changes never overwrite history.

Life and honorary memberships do not renew: the annual rollover creates
a zero-due, already-ACTIVE membership for such households each period,
so every "financial for period X" query remains a single uniform join
with no special cases.

### MembershipTypePrice

```text
MembershipTypePrice
  membership_type_id
  membership_period_id
  amount_cents
```

One row per type per period, written when the period is created. This
makes business rule 10 (preserve historical prices) structural rather
than aspirational, and matches how societies talk about fees
("2025/26: Single $45, Household $65"). Money is integer cents
throughout the schema — no floating point.

### MembershipPeriod

Defines each annual membership year.

```text
MembershipPeriod
  membership_period_id
  name                   e.g. "2026–2027"
  start_date
  end_date
  renewal_open_date
  late_joining_cutoff
```

This is preferable to deriving membership years implicitly from dates. It also supports the society's rule that a person joining after a specified date may be financial for the following membership year.

### Membership

Represents a single or household membership for a particular membership period.

```text
Membership
  membership_id
  membership_period_id
  membership_type_id
  household_id           NOT NULL
  status
  application_date
  approved_date
  start_date
  end_date
  amount_due_cents
  ceased_date            nullable
  cessation_reason       nullable   RESIGNED / DECEASED / OTHER
```

Every membership belongs to a household — a single membership uses a
one-person household. Making this mandatory keeps every billing,
renewal and reporting query on one code path. There is exactly **one
membership per household per period**: `UNIQUE (household_id,
membership_period_id)`.

Status values (state only — the *reason* a membership ended lives in
`cessation_reason`, not in the status):

```text
APPLIED
PENDING_PAYMENT
ACTIVE
LAPSED
CEASED
```

A member dying is never a membership status: it is
`Person.deceased_date` plus an end date on the MembershipPerson row —
in a household membership, one member's death does not end the
membership. A single-person household's membership becomes CEASED with
`cessation_reason = DECEASED`.

There is deliberately no `amount_paid` column: amounts paid are derived
from `PaymentAllocation`. A stored running total is a denormalized copy
that drifts the first time a payment is corrected.

`amount_due_cents` is the billed-amount snapshot taken at rollover (or
application) time. `start_date`/`end_date` default from the membership
period and diverge only for late joiners under the
`late_joining_cutoff` rule.

A new membership row should normally be created for each annual renewal. Do not overwrite the previous year's membership record.

### MembershipPerson

Associates named people with a particular membership.

```text
MembershipPerson
  membership_id
  person_id
  membership_role
  is_statutory_member
  has_voting_rights
  eligible_for_committee
  start_date
  end_date
```

This table is important even where a membership is associated with a household.

`HouseholdPerson` records that a person belongs to a household. `MembershipPerson` records that the person was covered by a particular membership during a particular period.

For example:

> Alice and Bob currently belong to the same household.

is a different fact from:

> Alice and Bob were both recognised members under the household membership for 2026–2027.

Keeping these facts separate preserves an accurate historical membership register.

## Relationships

```text
Household 1 ─── * HouseholdPerson * ─── 1 Person

MembershipPeriod 1 ─── * Membership
MembershipType   1 ─── * Membership
Household        1 ─── * Membership

Membership 1 ─── * MembershipPerson * ─── 1 Person
```

A single membership always uses a one-person household (see the
Membership section — `household_id` is NOT NULL).

A household membership normally has:

- one household;
- two or more current household-person associations;
- one membership for the relevant membership period;
- one membership-person row for every person formally covered.

## Formal member status

The society should determine whether a household membership means:

1. every named adult is formally a member with their own voting rights; or
2. one person is the formal member while other household occupants receive membership benefits.

This is a constitutional and governance question, not merely a billing question.

The database should therefore record formal membership and voting status for each person rather than assuming that every household occupant has identical rights.

Recommended fields include:

```text
MembershipPerson
  is_statutory_member
  has_voting_rights
  eligible_for_committee
  start_date
  end_date
```

This also allows household members who are children, non-voting associates, or publication recipients to be distinguished from formal voting members.

For the Yass & District Historical Society the constitutional question
is settled (2026-07-17): **both adults in a Household membership are
statutory voting members** — the rollover defaults both flags true for
adult household members; children and associates are flagged
individually.

## Addresses and contact details

Do not store all contact information directly on the membership record. Contact details belong primarily to people and households.

Address rows are **owned**, not shared: the address columns live
directly on the household/person address row. (An earlier draft had a
standalone `Address` entity M:N-linked from both — dropped 2026-07-17:
address rows are never actually reused across owners at this scale, and
sharing makes "edit this household's address" ambiguous.)

### HouseholdAddress

```text
HouseholdAddress
  household_address_id
  household_id
  address_type           POSTAL / RESIDENTIAL
  line_1
  line_2
  locality
  state
  postcode
  country
  valid_from
  valid_to
  is_preferred
```

### PersonAddress

```text
PersonAddress
  person_address_id
  person_id
  address_type
  line_1
  line_2
  locality
  state
  postcode
  country
  valid_from
  valid_to
  is_preferred
```

A household will usually have a shared postal address, but an individual may have a different residential or correspondence address.

### EmailAddress

```text
EmailAddress
  email_id
  person_id
  email
  is_primary
  valid_from
  valid_to
```

Email addresses are **not unique across people** — couples sharing one
email address is common in this demographic. They are stored lowercase
and matched case-insensitively (`lower(email)` index). Any flow that
looks a person up by email (magic-link recovery, import dedup) must
handle "this address matches more than one person".

### PhoneNumber

```text
PhoneNumber
  phone_number_id
  person_id
  number
  phone_type             MOBILE / HOME / WORK
  is_primary
  valid_from
  valid_to
```

Each person should be able to have their own email address and telephone number, even when postal correspondence is sent once per household.

## Communication preferences

Communication preferences should be associated with the relevant person or household.

```text
CommunicationPreference
  communication_preference_id
  person_id              nullable
  household_id           nullable
  communication_type     NEWSLETTER / RENEWAL / EVENTS / GENERAL
  delivery_method        EMAIL / POST / SMS / NONE
  consent_status
  effective_from
  effective_to
```

Exactly one of `person_id` / `household_id` is set (CHECK constraint).

This supports cases such as:

- one newsletter per household;
- separate renewal notices to each member;
- one person preferring email while another prefers post;
- recording consent for optional communications.

## Payments

Membership and payment records should be separate.

### Payment

```text
Payment
  payment_id
  received_date
  amount_cents
  payment_method
  payer_person_id        nullable
  bank_reference         nullable
  external_transaction_id nullable, UNIQUE where set
  reconciliation_status
  recorded_by            who entered it (admin username, or "stripe-webhook")
  recorded_at
  notes
```

`external_transaction_id` (e.g. the Stripe payment-intent id) carries a
partial UNIQUE constraint: payment webhooks are delivered at-least-once,
and the constraint is what makes a redelivered webhook a no-op instead
of a double-recorded payment. `recorded_by`/`recorded_at` are the audit
trail — committees change treasurers.

Suggested payment methods include:

```text
CASH
CHEQUE
BANK_TRANSFER
STRIPE
OTHER
```

### PaymentAllocation

Associates a payment with one or more obligations.

```text
PaymentAllocation
  payment_allocation_id
  payment_id
  allocation_type        MEMBERSHIP / JOURNAL / DONATION / OTHER
  membership_id          nullable — set for MEMBERSHIP, and for JOURNAL
                         where the add-on rides a membership renewal
  amount_cents
```

This allows a single payment to cover:

- annual membership;
- a printed journal subscription (the Boongaroon hardcopy add-on);
- a donation;
- another society purchase.

It also supports partial payments, corrections, refunds, and bank reconciliation.

An earlier draft referenced `donation_id` / `publication_order_id`
tables that were never defined; the type discriminator plus the
nullable membership FK covers v1 (dropped 2026-07-17). If dedicated
Donation or PublicationOrder tables are ever warranted, the enum rows
migrate into them.

## Publication preferences

A printed or electronic journal subscription should not be embedded in the membership type unless it is inseparable from membership.

```text
PublicationPreference
  publication_preference_id
  person_id              nullable
  household_id           nullable
  publication_id
  delivery_method        EMAIL_PDF / POSTAL_PRINT / NONE
  quantity
  effective_from
  effective_to
```

This allows publication delivery to be changed without altering the underlying membership category.

## Minimum practical schema

A robust first version should contain at least:

```text
Person
Household
HouseholdPerson
MembershipType
MembershipTypePrice
MembershipPeriod
Membership
MembershipPerson
Payment
PaymentAllocation
HouseholdAddress
EmailAddress
PhoneNumber
CommunicationPreference
```

## Recommended business rules

1. Every formal member must have a `Person` record.
2. Every household membership must identify all people covered by it.
3. Formal voting status must be recorded per person.
4. Household composition must not be used to infer historical membership.
5. A new `Membership` record should be created for each annual period.
6. Amounts paid are derived from `PaymentAllocation`, never stored on the membership record.
7. A lapsed membership must not cause the associated person or household to be deleted.
8. Contact details should retain effective dates where practical.
9. A household should have one nominated primary contact, but each person may have individual contact details.
10. Membership pricing changes should preserve historical prices (structural, via `MembershipTypePrice`).
11. One membership per household per membership period (unique constraint).
12. External payment transaction ids are unique where present — this is what makes payment-webhook redelivery idempotent.

## Example

A household consisting of John and Mary Smith might be represented as follows:

```text
Person
  P1001  John Smith
  P1002  Mary Smith

Household
  H2001  Smith household
         primary contact: P1001

HouseholdPerson
  H2001  P1001  MEMBER
  H2001  P1002  PARTNER

MembershipPeriod
  MP2026  2026–2027

Membership
  M3001
  period: MP2026
  type: HOUSEHOLD
  household: H2001
  status: ACTIVE

MembershipPerson
  M3001  P1001  statutory_member=true  voting=true
  M3001  P1002  statutory_member=true  voting=true
```

When the household renews for 2027–2028, a new `Membership` is created. The previous membership and its membership-person rows remain unchanged as the historical record.

## Known later additions

Tables that arrive with the change request that needs them, not in the
initial schema: renewal-token storage for magic payment links (CR-004:
token hash, membership, expiry, used_at), the email send log (CR-005),
`person.keycloak_subject` nullable+unique for member self-serve
(CR-006), and publication tables if `PublicationPreference` graduates
beyond the JOURNAL allocation type.

## Revision history

**2026-07-17** — reviewed against the [roadmap](ROADMAP.md) before
CR-001; amendments applied in place:

1. `Membership.household_id` NOT NULL (single memberships always use a
   one-person household) + `UNIQUE (household_id, membership_period_id)`.
2. Dropped `Membership.amount_paid` — derived from `PaymentAllocation`.
3. Prices moved off `MembershipType` into `MembershipTypePrice`
   (per type per period); money is integer cents everywhere.
4. Primary contact recorded only on `Household`; dropped the
   `HouseholdPerson.is_primary_contact` flag.
5. Membership status reduced to state only (APPLIED, PENDING_PAYMENT,
   ACTIVE, LAPSED, CEASED); RESIGNED/DECEASED/OTHER moved to
   `cessation_reason`. DECEASED is never a membership status.
6. `PaymentAllocation` reshaped: `allocation_type` discriminator +
   nullable `membership_id`, replacing references to undefined
   Donation/PublicationOrder tables. Payment methods trimmed to those
   this app records (CARD/WOOCOMMERCE removed; card = STRIPE here).
7. `Payment` gains `recorded_by`/`recorded_at` audit columns and a
   partial UNIQUE on `external_transaction_id` (webhook idempotency).
8. Life/honorary convention stated: rollover creates $0 ACTIVE
   memberships each period.
9. Addresses owned not shared: standalone `Address` entity folded into
   `HouseholdAddress`/`PersonAddress`.
10. Email addresses: explicitly non-unique (shared household emails),
    matched case-insensitively.
11. `CommunicationPreference`: person XOR household CHECK.
12. `MembershipPerson` field lists unified on `start_date`/`end_date`;
    noted the society's decision that both adults in a Household
    membership are statutory voting members.
