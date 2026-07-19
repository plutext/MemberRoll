# Gap analysis: MemberRoll for a new political party — The Riverina State

Status: EXEMPLAR (2026-07-20) — a demonstration of the fork workflow in
`docs/fork-philosophy.md`, prepared from **public information only**.
The party has not commissioned or reviewed this document, its
constitution has not been sighted, and no contact has been made.
Regulatory thresholds were checked July 2026 against the electoral
commissions' own pages (sources at the end) but electoral law changes —
a real fork re-verifies everything here and reads the party's actual
constitution before implementing anything.

## The organisation

[The Riverina State](https://theriverinastate.com.au/) is a political
movement advocating a new Australian state in the Riverina region. Two
facts shape everything below:

- **It is cross-border.** The site maintains both NSW and Victorian
  maps; the movement's footprint spans the Murray. Membership therefore
  splits across two state jurisdictions plus the Commonwealth.
- **It is mid-registration now.** The site's news reports the Victorian
  Electoral Commission posting confirmation letters to members
  (June 2026) — the VEC's membership-verification step in action. The
  compliance workflow this analysis centres on is not hypothetical for
  them; it is this quarter's problem.

## Regulatory targets (the party's real governing documents)

A party's constitution matters (NSW registration requires a written
constitution), but the requirements that drive software design come from
electoral law:

| Register | Members required | Verification method |
|---|---|---|
| AEC (federal) | **1,500** enrolled voters; application lists 1,500–1,650 | ABS-designed random sample contacted to confirm membership; members must be unique **across parties** (one person counts for one party only); minimum ~3 months processing |
| NSW Electoral Commission | **750** members + written constitution | Members must be enrolled |
| Victorian Electoral Commission | **500** eligible members enrolled in Victoria | VEC **writes to members** asking them to confirm eligible membership |

Two consequences dominate the design:

1. **A member who cannot be matched to the electoral roll is invisible**
   to every one of these counts, regardless of having paid.
2. **A sampled member who denies membership (or doesn't respond) sinks
   the application.** The party's crucial pre-lodgement activity is
   therefore *re-confirming* members — a communications campaign with
   response tracking, not a report.

## What transfers unchanged (~80%, and it is the hard 80%)

Keycloak identity and the PKCE login; Stripe Checkout + magic-link
pay-without-login (a party collects membership fees and donations — the
Donate button is already on their site); receipts (CR-012); segment
email (CR-005); SMS delivery (CR-016 — a *high*-value channel for
confirmation campaigns); the committee register (CR-013 — a party
executive is office-bearers with AGM-to-AGM terms); reconciliation
export (CR-015); the whole production topology and deploy assets
(CR-008). The society-specific pieces that don't transfer are data, not
code: the Sep–Aug membership year, the journal add-on, the fee schedule
— all period/type rows.

## The gaps — proposed fork CRs

### F-001: Individual-only membership (degenerate household)

Parties count *persons*; every member must individually match the roll.
But the right change is **not** excising the household model — it is
constraining it: every membership type gets `minimum_people =
maximum_people = 1`, relationship type is always MEMBER (so the
voting-rights rule is trivially uniform), and the UI stops surfacing the
household concept (the wizard creates a one-person household silently).
The schema keeps its shape, `insertMembershipPerson` keeps its one rule,
and the upstream matrix mostly survives.

*Rejected: schema excision.* Removing `household`/`household_person`
touches every store and most of the matrix for zero functional gain —
the classic fork mistake the charter's load-bearing list exists to
prevent. *PR-back candidate:* an upstream "individual mode" UI
affordance (many organisations are individual-only) — the data model
already permits it; only the admin UI assumes households.

### F-002: Electoral-roll alignment on the person record

To be countable, a person must match their enrolment. The person record
gains: name-as-enrolled (where it differs from preferred name),
**residential address distinct from postal** (enrolment follows
residence; societies only need postal), date of birth (already in the
schema — CR-011's under-18 exclusion relies on it), and **enrolled
jurisdiction** (`NSW | VIC | OTHER | UNKNOWN`, defaulted from the
residential address but overridable — the cross-border reality means
address state and enrolment state usually agree but must be assertable).
A silent-elector/suppression flag follows CR-011's
`suppress_register_details` pattern. Members can self-check enrolment
via the AEC's facility; the fork stores the *assertion*, it does not
scrape the roll.

### F-003: Registration-compliance dashboard and list exports

The CR-011 register-export analogue, and the fork's crucial read
surface:

- Live counts of confirmed, roll-matchable members per register —
  against 1,500 (AEC), 750 (NSW), 500 (Vic) — with the shortfall
  explicit.
- Commission-format list exports (name, residential address, DOB,
  contact) per jurisdiction, honouring the suppression flag's rules.
- **Lodgement snapshots**: the list actually submitted is frozen at
  submission (the CR-005 `email_send` snapshot discipline — a later
  member edit must never rewrite the record of what was lodged).

### F-004: Confirmation campaign with response tracking

The pre-lodgement re-confirmation workflow, built on seams that already
exist:

- A campaign is a CR-005/CR-016 send ("reply YES to confirm your
  membership of The Riverina State" / a one-click confirm link on the
  CR-004 token pattern) to a jurisdiction segment.
- Responses land on `communication_preference.consent_status` — the V1
  column CR-005 deliberately left NULL for exactly this kind of opt-in
  flow — as CONFIRMED / DENIED / NO_RESPONSE with dates.
- **"Count me for registration" is a distinct consent**, not membership
  itself: federally one person counts for one party only, so the party
  must ask and record it separately. A DENIED or unconsented member is
  excluded from F-003's exports automatically — the software makes the
  dangerous list (members who would fail sampling) impossible to lodge
  by accident.

### F-005: Donations compliance (flagged, not designed — the real risk)

Political donations are a different legal universe from a society's:
NSW electoral funding law caps political donations, prohibits entire
donor classes, and requires disclosure; Victoria and the Commonwealth
have their own caps/disclosure regimes; and party donations are not DGR
(the upstream DGR knob is irrelevant here — deductibility of political
contributions has its own rules). This CR is deliberately left as
PLANNED: it needs real design work against current law in three
jurisdictions and **professional review before implementation** — the
charter's limit that an agent drafts compliance design but does not sign
it off applies with full force. Until it lands, the fork should take
donations offline (bank transfer + manual recording, which the ledger
already supports) rather than ship an unreviewed online-donation path.

## Verification

The fork's discipline is inherited: upstream matrix green first (the
charter baseline), then `F*-*` rows per CR — the F-003 export rows
assert exact CSV bodies against fixture members in known jurisdictions,
the F-004 rows drive a confirmation round trip (send → token confirm →
consent_status row → export inclusion flips), F-001 rows prove a
one-person wizard path and that the two-person path is gone. Browser
walkthroughs for the dashboard and the wizard.

## Shape of the effort

Four fork CRs of ordinary size plus one (F-005) needing genuine design:
comparable to what this repo has repeatedly shipped as one-or-two-day
CR sessions (see `codebase-size-and-effort-estimate.md`, "What actually
happened"). The inherited 80% — identity, payments, communications,
deploy — is precisely the part that consumed the estimate's
person-months. No configuration surface could have anticipated "satisfy
ABS-sampled membership testing across three electoral registers"; this
is the fat tail of requirements that makes fork-over-config win.

## Sources

- [AEC — Party registration FAQs](https://www.aec.gov.au/faqs/party-registration.htm)
- [AEC — Party registration: recent changes to the Electoral Act](https://www.aec.gov.au/FAQs/party-reg-changes.htm)
- [AEC — Guide for registering a party (PDF)](https://www.aec.gov.au/Parties_and_Representatives/Party_Registration/guide/files/party-registration-guide.pdf)
- [NSW Electoral Commission — Register a party](https://elections.nsw.gov.au/political-participants/political-parties/register-a-party)
- [Victorian Electoral Commission — Register a party](https://www.vec.vic.gov.au/candidates-and-parties/registered-political-parties/register-a-party)
- [The Riverina State](https://theriverinastate.com.au/) (movement aims, cross-border maps, VEC confirmation-letters news item, June 2026)
