# CR 011: Constitutional register compliance — export, suppression, voting age, suspension

Status: PROPOSED (2026-07-18)

## Problem

The constitution (updated 6/2024) was read against the implemented
system on 2026-07-18. Nothing built contradicts it, but four gaps
surfaced — none blocking day-to-day use, all worth closing so the app's
register can serve as *the* register of members under clause 4 rather
than a shadow of a paper one. Recorded as one CR with an independent
stage per gap; stages can land separately and in any order after
Stage 2 (Stage 1 consumes Stage 2's flag if both land).

## Approach

### Stage 1 — Register-of-members export (clause 4)

Clause 4(2) requires a register of every member recording: full name;
a residential, postal **or** email address; the date the person became
a member; and, for ceased members, the date they ceased. It must be
convertible to hard copy (4(2)(e)) and inspectable by members (4(2)(d)).

A new admin CSV export (beside the existing AGM/mailing-label/financial
exports): one row per person who holds or has held a formal
(relationship_type MEMBER) membership place, with:

- full name;
- best address: preferred postal address, else primary email (clause
  4(2)(b) accepts any one of the three);
- date became a member: earliest membership_person.start_date across
  their memberships. **Known limitation, stated in the export header
  docs**: CR-002's import did not capture original join dates, so for
  imported members this is the earliest *imported* period, not their
  true historical join date;
- date ceased (blank while current): derived from the person's last
  membership's cessation/lapse where they hold no current membership —
  the exact derivation to be pinned in the implementation, mapped to
  clause 12 (death, resignation, expulsion, non-payment);
- honours the Stage 2 suppression flag when present: a suppressed
  member's row carries the name only (clause 4(5)).

Unlike the AGM register (ACTIVE voting members only), this export
includes lapsed/ceased members with their dates — that is the point.

### Stage 2 — Clause 4(5) suppression flag

A member may request their details (other than their name) not be made
available for inspection. Add `person.suppress_register_details boolean
NOT NULL DEFAULT false` (V7 or next free migration), a checkbox in the
admin person form, and honour it in the Stage 1 export (name-only row).
It does NOT affect operational use (renewal emails, admin screens) —
clause 4(5) is about register inspection, not about the society
corresponding with its own member.

### Stage 3 — Under-18 voting exclusion (clause 34(1)(a))

A member under 18 is not entitled to vote. `has_voting_rights` is
granted to every MEMBER-relationship person regardless of age, and
date_of_birth is nullable, so the AGM register export could list an
under-18 member. Change the AGM export to exclude members whose
date_of_birth shows them under 18 **on the export date**; a member with
no recorded DOB stays included (the common case — flagging unknowns
would drown the real signal at this society's demographics). Note the
rule in the user manual's export section. No schema change; the
underlying membership_person flags stay as they are (the constitution's
test is age *at the meeting*, so export-time derivation is more correct
than a stored flag).

### Stage 4 — Suspension (clause 7): documented manual procedure

Clause 7 lets the committee suspend (not only expel) a member.
Suspension is temporary; our only terminal state is CEASED. At this
society's scale this stage adds **no schema or code**: document the
manual procedure in the user manual (record the suspension and its
period in the person's notes; do not cease the membership; the member
stays off nothing operationally — the committee manages the practical
consequences). Revisit as a real status only if it ever bites.

### Rejected alternatives

- A stored `is_eligible_voter` recomputed from DOB — the age test is
  meeting-date-relative; deriving at export time is simpler and can't
  go stale.
- Making suppression hide the member from admin screens — clause 4(5)
  governs member inspection of the register, not administration.
- A suspension status/workflow — no current need; pure speculation.

## Verification plan

Stage-scoped matrix rows (extend verify-matrix.sh as each stage lands):

| # | stage | case | expect |
|---|---|---|---|
| 1 | 1 | guest / user / noaud GET the register export | 403 / 403 / 401 |
| 2 | 1 | admin GET register export | 200 text/csv; fixture people present with joined dates; a ceased fixture carries its ceased date; an email-only person shows the email as address |
| 3 | 1 | imported-member row | joined date = earliest imported period start |
| 4 | 2 | PUT person with suppress flag; GET echoes | flag persisted |
| 5 | 2 | register export for suppressed person | name column only, other columns blank |
| 6 | 3 | AGM export with an under-18 MEMBER fixture (DOB set) | absent from the CSV |
| 7 | 3 | AGM export with a no-DOB MEMBER | present |
| 8 | 4 | — | user-manual section exists (review, not scripted) |

Browser: person-form checkbox round trip; export downloads from the
Renewals section.

## Results

(to be recorded when implemented)

## Follow-ups / amendments

- **2026-07-23 — committee answers, per stage:**
  - Stage 1 (register-of-members export): confirmed wanted, **lower
    priority** — keep on the TODO list, no urgency.
  - Stage 2 (clause 4(5) address suppression): whether any member has
    asked for suppression — **to be advised**.
  - Stage 3 (under-18 voting exclusion): whether there are any under-18
    members — **to be advised**.
  - Stage 4 (clause 7 suspension as a documented manual process, not a
    system feature): **agreed** — proceed as proposed, user-manual
    section only.
- **2026-07-23 — Stage 1 delivery vehicle:** CR-019 (reports & exports)
  takes the register-of-members export as its Report A, implemented to
  this doc's Stage 1 specification (which stays authoritative here).
  Stages 2–4 remain with this CR.
- **2026-07-23 — Stage 1 DELIVERED** by CR-019 (same day):
  `GET /api/admin/export/register-of-members.csv` on the new Reports
  page. The derivations this doc left "to be pinned" are pinned in
  CR-019's Results (and in `ReportStore`'s javadoc): date became a
  member = earliest `membership.start_date` over formal places (the
  imported-data limitation stated on the page and in the manual); a
  person is current while holding a formal place on an ACTIVE or
  PENDING_PAYMENT membership whose end date has not passed; date ceased
  = `ceased_date` for a CEASED last membership, else the end of the last
  membership year capped at today (the clause 12 non-payment mapping).
  `ReportStore.registerOfMembers` is the marked spot where the Stage 2
  suppression flag gets honoured when it lands. Verification: CR19-01/02/
  03 and 13 matrix rows (this doc's Stage 1 rows 1–3 equivalents) — see
  CR-019 Results.
