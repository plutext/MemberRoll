# CR-032 — per-payment lines in the Xero journal

## Problem

The CR-015 Xero journal (`GET /api/admin/payments/export/xero-journal.csv`)
books a window's Stripe payments as one line per *account*:

```
Narration,Date,AccountCode,TaxRate,Amount
MemberRoll Stripe reconciliation 2026-08-01..2026-09-16,2026-09-10,640,BAS Excluded,1490.00
MemberRoll Stripe reconciliation 2026-08-01..2026-09-16,2026-09-10,244.8,BAS Excluded,-1185.00
MemberRoll Stripe reconciliation 2026-08-01..2026-09-16,2026-09-10,244.14,BAS Excluded,-50.00
MemberRoll Stripe reconciliation 2026-08-01..2026-09-16,2026-09-10,242.4,BAS Excluded,-255.00
```

That balances and zeroes the clearing account, but once imported Xero
shows only four anonymous totals. The treasurer's request (2026-09-21):
see *each member's* membership payment, and any donation, in Xero — the
granularity the "Download CSV" reconciliation export already has. Today
that means cross-referencing the CSV by hand whenever a Xero figure is
questioned.

## Objective

Emit the journal with **one credit line per payment × allocation type**,
each carrying a per-line Description naming the payment, so the income
accounts' transaction lists in Xero read like the reconciliation CSV.
Nothing else about the feature moves: same button, same filters, same
STRIPE-forcing, same 409-until-mapped gate, same clearing-account
pattern, still balanced to zero by construction.

## Design

### 1. Xero's format already has the slot

Xero's manual-journal import template is
`*Narration, *Date, Description, *AccountCode, *TaxRate, *Amount,
TrackingName1, TrackingOption1, TrackingName2, TrackingOption2`.
`Description` is a free-text **per-line** field, unused by CR-015. Lines
sharing a Narration + Date still fold into ONE journal on import, so
per-payment lines cost nothing in Xero-side ceremony. Two constraints
from Xero Central that shape the output:

- **Column headings must match the template** or the import fails. The
  CR-015 header omitted `Description` (and the `*` markers); this CR
  emits all ten of the template's headings verbatim, `*` included, with
  the four tracking cells left empty — a file Xero cannot tell from
  its own template. Dates stay ISO as in CR-015 (not re-examined here;
  the real-Xero import in the verification plan is where a date-format
  complaint would surface).
- **300 lines per import file** (spreadsheet-editor path). See §3.

### 2. The lines

For each payment row of `ReconciliationStore.export(filter)` (the same
data "Download CSV" prints, STRIPE-forced as before), one line per
non-zero allocation column:

```
*Narration,*Date,Description,*AccountCode,*TaxRate,*Amount,TrackingName1,TrackingOption1,TrackingName2,TrackingOption2
MemberRoll Stripe reconciliation 2026-08-01..2026-09-16,2026-09-10,Stripe payments 2026-08-01..2026-09-16 (23 payments),640,BAS Excluded,1490.00,,,,
MemberRoll Stripe reconciliation 2026-08-01..2026-09-16,2026-09-10,#412 2026-08-03 Jane Smith (Smith household) — membership,244.8,BAS Excluded,-60.00,,,,
MemberRoll Stripe reconciliation 2026-08-01..2026-09-16,2026-09-10,#412 2026-08-03 Jane Smith (Smith household) — donation,244.14,BAS Excluded,-20.00,,,,
MemberRoll Stripe reconciliation 2026-08-01..2026-09-16,2026-09-10,#413 2026-08-03 Adam Brown (Brown household) — membership,244.8,BAS Excluded,-45.00,,,,
MemberRoll Stripe reconciliation 2026-08-01..2026-09-16,2026-09-10,#420 2026-08-19 Adam Brown (Brown household) — membership refund,244.8,BAS Excluded,45.00,,,,
```

- **Description** = `#<payment id> <received date> <payer> (<household>)
  — <type>`; a negative payment's line says `<type> refund`. The
  payment id is the receipt number (CR-012), so a Xero line is
  traceable back to the counter in one step. Payer falls back to the
  household name and then to the method when the payment carries no
  payer (a Stripe webhook payment has a payer only when the pay link's
  person resolved). The type word is the lower-cased allocation type
  (`membership`, `journal`, `donation`, `other`).
- **Amount** per line is `-allocationCents` — the CR-015 sign rule
  unchanged (negative = credit; a refund's allocation is negative so
  its line comes out positive, a debit, exactly as the aggregate line
  used to flip). Zero allocations produce no line.
- **Clearing debit stays ONE line** for the window's gross Stripe
  total, described `Stripe payments <window> (<n> payments)`. Rejected:
  a clearing debit per payment. It would double the line count against
  the 300-line cap for no reconciliation gain — the payout that clears
  the account is one bank line regardless, and the clearing account's
  job is to hit zero, not to carry the member detail (the income
  accounts do that).
- Ordering: the clearing line first, then payments in the export's
  order (received date, then id), types in the fixed
  MEMBERSHIP / JOURNAL / DONATION / OTHER order — the CSV's column
  order.
- Balance: each payment's four allocation columns sum to its gross
  (CR-015 invariant, enforced by the store's fold), so Σ credits =
  −Σ gross = −(clearing debit). Unchanged proof, more lines.

### 3. The 300-line cap — split into parts

A window whose line count (1 clearing + Σ per-payment lines) exceeds
300 is emitted as **several journals in one file**: payments are
chunked so no journal exceeds 300 lines, each chunk gets its own
clearing debit for *its* gross and the narration suffix ` (part k of
n)`, and Xero imports the file as n balanced journals. The
`part` boundary is by payment (a payment's lines never straddle two
journals). A single-part file carries no suffix, so the common case
(the ~75-renewal year, ~90–120 lines) is byte-for-byte the §2 shape.

Rejected: refusing with a 400 and telling the treasurer to narrow the
window. The window is chosen by payout rhythm, not by line count, and
a 400 from a download button is a dead end.

### 4. Aggregate form: replaced, not kept behind a flag

The per-line file is a strict superset (Xero shows each account's
total in its own reports), and one shape is one shape to keep green
in the matrix. No `?detail=` switch. The reconciliation CSV's trailing
summary block still gives the by-type totals for a treasurer who wants
the four numbers on paper.

### 5. Code

`AdminPaymentsResource.exportXeroJournal` iterates `export.rows()`
instead of `export.totals()`; `journalLine` gains a `description`
argument; a small `chunk` step implements §3. `ReconciliationStore.Row`
already carries everything the description needs. **No store change,
no schema, no UI change, no new endpoint, no role change** (still
admin-only under CR-024's split — CR24-60 stays).

## What is deliberately *not* changing

- The reconciliation CSV and its JSON preview (the treasurer's other
  two views) — untouched.
- STRIPE-forcing, the 409-until-mapped gate, the mapping endpoints,
  the mark-reconciled step, the `maxPaymentId` bound.
- The journal date rule (last received date in the window, else the
  `to` bound, else today).
- Tracking categories: not emitted. If the society ever wants a
  tracking category per membership type, that is a mapping-blob
  addition, not a journal-shape change.
- Stripe fees: still the CR-015/CR-029 follow-up (a fee line would
  need per-payment fee capture from the webhook's balance transaction).

## Verification plan

Matrix (`server/verify-matrix.sh`, CR15 block rewritten where the
aggregate shape was asserted, plus CR32 rows; fixture = the existing
CR15 STRIPE payments PB (60.00: 45 membership + 10 journal + 5
donation), PD (30.00 membership), PE (−30.00 membership) in the March
window, PF (−20.00 membership) in April):

| Row | Check | Expect |
|---|---|---|
| CR15-06 | journal 409 with no mapping | 409 (unchanged) |
| CR15-06f | Σ Amount over all lines | 0.00 (unchanged) |
| CR15-06g | header is the Xero template's ten columns verbatim | `*Narration,*Date,Description,*AccountCode,*TaxRate,*Amount,TrackingName1,TrackingOption1,TrackingName2,TrackingOption2` |
| CR15-06h | clearing debit = gross | 60.00 on code 1200 (unchanged value) |
| CR15-06i | membership credit is now three lines summing to −45.00 | −45.00 |
| CR15-06j | tax rate every line | yes (unchanged) |
| CR15-06k | one shared narration | 1 (unchanged) |
| CR15-06l | STRIPE forced | 60.00 (unchanged) |
| CR15-06m/n/o | April refund window: membership line +20.00 / clearing −20.00 / balances | unchanged values |
| CR32-01 | line count = 1 + 3 (PB) + 1 (PD) + 1 (PE) | 6 |
| CR32-02 | PB's three lines carry `#<PB>`, the payer name, one per type | membership/journal/donation |
| CR32-03 | PB membership line amount | −45.00 on 4000 |
| CR32-04 | PB journal line amount | −10.00 on 4010 |
| CR32-05 | PB donation line amount | −5.00 on 4020 |
| CR32-06 | PE's line says `membership refund` and is +30.00 | debit |
| CR32-07 | clearing description names the payment count | `(3 payments)` |
| CR32-08 | no `(part` suffix on a small window | 0 |
| CR32-09 | description column non-empty on every line | yes |
| CR24-60 | manager 403 | unchanged |

Browser: Reports page → Download Xero journal → open the file (eyeball
the descriptions). Real-data check: import the exported file into the
society's Xero as a draft journal and confirm the per-line descriptions
show against the income accounts.

## Verification results

**2026-09-21, dev stack (Opus 5).** Three full matrix runs while
iterating: 1019/17 → 1021/16 → **1027/16**. Every CR15-06\* (15 rows,
rewritten for the ten-column shape) and CR32-\* (19 rows) row green,
CR24-60 (manager 403) unchanged. The one CR-032 miss on the first run
was the matrix's own fixture, not the code: the CR15 payments are
psql-seeded with no payer, so the description took the household-only
fallback; PB now carries `payer_person_id` (CR32-02b asserts the full
`#id date payer (household)` shape, CR32-02c the household-only one on
PD).

Added beyond the plan: CR32-10 (tracking cells empty on every line),
CR32-11 (payment order = export order), and CR32-12a–f — a 305-payment
fixture in a 2098 window proving the §3 split: 307 lines, two
narrations `(part 1 of 2)`/`(part 2 of 2)`, part sizes 300 + 7, each
part sums to 0.00, clearing debits 2990.00 + 60.00, fixture deleted
after (row f asserts 0 remain).

Sample output from the accumulated dev fixtures (72 STRIPE payments in
the 2099-03 window; the `#…` descriptions are quoted by commons-csv
because they start with `#` — valid CSV, Xero-neutral):

```
*Narration,*Date,Description,*AccountCode,*TaxRate,*Amount,TrackingName1,TrackingOption1,TrackingName2,TrackingOption2
MemberRoll Stripe reconciliation 2099-03-01..2099-03-31,2099-03-20,Stripe payments 2099-03-01..2099-03-31 (72 payments),640,BAS Excluded,1440.00,,,,
MemberRoll Stripe reconciliation 2099-03-01..2099-03-31,2099-03-20,"#21 2099-03-20 Rec2926639 HH household — membership",244.8,BAS Excluded,-45.00,,,,
MemberRoll Stripe reconciliation 2099-03-01..2099-03-31,2099-03-20,"#21 2099-03-20 Rec2926639 HH household — journal",242.4,BAS Excluded,-10.00,,,,
MemberRoll Stripe reconciliation 2099-03-01..2099-03-31,2099-03-20,"#21 2099-03-20 Rec2926639 HH household — donation",244.14,BAS Excluded,-5.00,,,,
MemberRoll Stripe reconciliation 2099-03-01..2099-03-31,2099-03-20,"#23 2099-03-20 Rec2926639 HH household — membership",244.8,BAS Excluded,-30.00,,,,
MemberRoll Stripe reconciliation 2099-03-01..2099-03-31,2099-03-20,"#24 2099-03-20 Rec2926639 HH household — membership refund",244.8,BAS Excluded,30.00,,,,
```

**The 16 standing failures are not this CR's** and predate it: the V2
seed's only yearly period, `2025-2026`, ended 2026-08-31, and since
then every "current membership" window in the app
(`current_date <= per.end_date`: lost-link, self-serve `/api/me/
membership`, the CR-021 sandbox pay-link, CR-007's
`hasCurrentMembership`) matches nothing on dev — the cargo log says
`lost-link: no current membership matches … — not sending` for each
(CR4-20b/c, CR4-22b, CR6-06c/d, CR6-08/b/c, CR7-25, CR21-06b/c and the
four CR21 count rows that cascade from 06b), plus the pre-existing
CR4-01c calendar flake. Follow-up (not started): give the matrix a
period covering today — either a seeded `2026-2027` in a V-migration
the prod DB would also accept, or a matrix-created period — and repoint
the 26 `2025-2026` fixture references at "the period covering today".

No browser walkthrough: the UI is untouched (same button, same URL,
`admin.js:1670`); the real-Xero import of a production export is the
treasurer's step and is still to be done.

## Amendment 2026-09-21 — one journal per payment (field feedback)

The treasurer imported the first export into the society's Xero. It
imported, with two findings:

1. **Mojibake**: the em dash in each description arrived as `â€”` —
   Xero/Excel read the UTF-8 file as Windows-1252. Fix: the journal is
   **ASCII only**. Descriptions use plain words, and `describePayment`
   passes names through `ascii()` (NFD-decompose, strip combining marks,
   `?` for whatever remains — `Zoë` → `Zoe`, asserted in CR32-12e). A
   UTF-8 BOM was rejected: it fixes Excel but Xero's importer would see
   `\ufeff*Narration` as a mismatched heading.
2. **Wrong column for the member text.** In Xero's account transaction
   list a manual-journal line shows the journal's **Narration**; the
   line Description is only visible inside the journal. So the
   per-payment text has to be the Narration — and Narration is
   journal-level (lines sharing Narration+Date fold into one journal;
   distinct narrations are distinct journals, each of which must
   balance). The design is therefore now **one journal per payment**:

   ```
   *Narration,*Date,Description,*AccountCode,*TaxRate,*Amount,TrackingName1,TrackingOption1,TrackingName2,TrackingOption2
   "#21 2099-03-20 Rec2926639 HH household - MemberRoll Stripe reconciliation 2099-03-01..2099-03-31",2099-03-20,Stripe payment (gross),640,BAS Excluded,60.00,,,,
   "#21 2099-03-20 Rec2926639 HH household - MemberRoll Stripe reconciliation 2099-03-01..2099-03-31",2099-03-20,Membership,244.8,BAS Excluded,-45.00,,,,
   "#21 2099-03-20 Rec2926639 HH household - MemberRoll Stripe reconciliation 2099-03-01..2099-03-31",2099-03-20,Journal,242.4,BAS Excluded,-10.00,,,,
   "#21 2099-03-20 Rec2926639 HH household - MemberRoll Stripe reconciliation 2099-03-01..2099-03-31",2099-03-20,Donation,244.14,BAS Excluded,-5.00,,,,
   "#24 2099-03-20 Rec2926639 HH household - MemberRoll Stripe reconciliation 2099-03-01..2099-03-31",2099-03-20,Stripe payment (gross),640,BAS Excluded,-30.00,,,,
   "#24 2099-03-20 Rec2926639 HH household - MemberRoll Stripe reconciliation 2099-03-01..2099-03-31",2099-03-20,Membership refund,244.8,BAS Excluded,30.00,,,,
   ```

   - Narration = `#<payment id> <received date> <payer> (<household>) -
     MemberRoll Stripe reconciliation <from>..<to>` (fallbacks as
     before; the member text leads because it is what the account list
     shows first, the window suffix says where the journal came from —
     the treasurer's second-round wording, 2026-09-21; an open bound is
     blank, `..2026-09-16`); Date = the payment's **received date** (a
     bonus: the P&L lands in the right month, where the aggregate
     journal put a whole window on its last date); Description = the
     line's part (`Stripe payment (gross)`, `Membership`, `Donation`,
     `… refund`), which is what Xero shows inside the journal.
   - The clearing debit is now **per payment** — §2's "one debit"
     rejection is withdrawn, since a per-payment journal needs its own
     debit to balance, and the clearing account listing each Stripe
     transaction individually matches Stripe's own balance list (a
     benefit for tracing a payout that spans a window).
   - §3's in-file "(part k of n)" split is **gone** — there is no
     balanced way to split a per-payment journal set inside one file
     that Xero would read differently, and the parts exist to satisfy
     one journal's balance, which each payment now does alone. Xero's
     300-line cap is enforced instead as a **400** naming the count
     (`this window needs 302 journal lines; Xero imports at most 300 per
     file — narrow the date range`), and `admin.js`'s `downloadFrom`
     now surfaces a JSON `{error}` body in the failure banner (it used
     to show only the status code) so the treasurer reads the remedy.
     The earlier "a 400 from a download button is a dead end" objection
     is met by the message; ~75 renewals a year is 150–225 lines, so a
     financial-year window fits and a month always does.
   - Line count per payment = clearing (when gross ≠ 0) + one per
     non-zero type. Lines per file ≤ 300 checked before writing.

**Verification (amendment):** matrix rows rewritten — CR15-06f now
"file balances", 06h "clearing debits = gross" (a sum), 06k "one journal
per payment" (3 narrations for 3 payments); CR32-01 eight lines (4+2+2),
CR32-02 PB's four Descriptions in order, CR32-02b/c the narration
shapes, CR32-02d journal dated on the receipt date, CR32-05b PB's
clearing +60.00, CR32-06 PE's refund journal (`Stripe payment (gross)=
-30.00,Membership refund=30.00`), CR32-07 every journal sums to 0,
CR32-08 the whole file is ASCII bytes, CR32-11 journal order, CR32-12
302 lines → 400 with the exact message, 12c/d 298 lines = 149 journals
export, 12e `Zoë` → `Zoe`; the second round added CR32-02e (open-bound
label `2099-03-01..`) and CR32-02f (narrations unique per payment).
Full matrix **1031/16** (the same 16 calendar failures). Sample above is
live dev output.
Still to confirm in Xero: the re-import of a real window with the new
shape (expected: one journal per payment, narration visible in the
account lists, no `â€”`).

## Follow-ups

- Matrix period staleness (above).
- The Stripe fee capture that would let each part's clearing debit be
  netted against a fee line (CR-015/CR-029 follow-up, unchanged).
