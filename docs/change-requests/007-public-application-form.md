# CR 007: Public application form — APPLIED workflow with committee approval

Status: PLANNED (pre-proposal notes recorded 2026-07-18 — this is not
yet the PROPOSED design; it records the constraints and open issues the
design must fold in when it is written)

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

To be confirmed when the proposal is written, not decided here:

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
- New-application notification to a configured society address.
- Whether approval auto-runs CR-006 provisioning stays as decided in
  CR-006: deferred; the admin presses the button.

## Outstanding issues

- **Entrance fee — DEFERRED (2026-07-18).** Clause 5(1) prescribes a
  $20 entrance fee for approved applicants, "or another amount
  determined by the committee", on top of the annual subscription.
  Whether an entrance fee currently applies is unknown — the $45/$65
  fees are committee-determined subscriptions and may or may not
  subsume it. If one applies, the approval flow must bill entrance +
  subscription and the payment model needs a shape for it (a one-off
  allocation line or a first-year price). Deferred until the society
  confirms; the proposal cannot be finalized without the answer.

## Approach

(to be written — the PROPOSED design)

## Verification plan

(to be written with the design)

## Results

(after implementation)

## Follow-ups / amendments

(dated additions)
