# CR-028: Household address editing

## Problem

`household_address` (CR-001, V1) has always been **write-once and
read-only**. It is written in exactly two places:

- `HouseholdStore.addPostalAddress` — called only by CR-007 application
  approval, inserting a single **preferred POSTAL** row.
- `ImportService.insertAddress` — called during CR-002 import, inserting
  a single **preferred POSTAL** row from the CSV's `line1..postcode`
  columns (line1 present ⇒ a row is written).

It is read in two places, both taking the household's `is_preferred DESC,
household_address_id DESC LIMIT 1` row (the preferred one, else the
newest), **type-blind**:

- `ReportStore.registerOfMembers` — the clause-4 register-of-members
  export (CR-019).
- `MembershipStore` — the address block on the membership card (CR-017)
  and payment receipt (CR-012).

There is **no GET, no update, no delete, and no UI**. Consequences:

- A household created by the new-member wizard (CR-010) or imported
  without address columns has **no address at all**, and one can never be
  added afterward.
- When a member moves house, the stored address **can never be
  corrected** — the register and their card keep showing the old one.
- The distinction the schema draws between POSTAL and RESIDENTIAL
  addresses is unreachable: every writer hard-codes POSTAL.

## Decision (from the requester)

- Expose the **full** address model in the editor: multiple rows, each
  with a **type** (POSTAL / RESIDENTIAL), the address fields, and a
  **preferred** flag.
- **Wholesale-replace** the set on save, exactly like a person's
  emails/phones (`PersonStore`). The schema's `valid_from`/`valid_to`
  columns would allow retaining superseded addresses as history; that is
  **explicitly deferred** — for now editing replaces the set and the old
  rows are gone.
- **Import populates a RESIDENTIAL address, marked preferred** (imported
  addresses are members' home addresses). This is a one-word change to
  `ImportService.insertAddress` (`'POSTAL'` → `'RESIDENTIAL'`).

## Approach

Mirror the established `PersonStore` emails/phones idiom — the payload
carries the whole list, the store deletes-all-then-reinserts in one
transaction — scoped to the **household detail dialog** on
`households.html`.

### Server

`HouseholdStore`

- New `record Address(String type, String line1, String line2,
  String locality, String state, String postcode, String country,
  boolean preferred)`.
- `Household` record gains `List<Address> addresses`.
- `get(handle, id)` loads the addresses (`ORDER BY is_preferred DESC,
  household_address_id`), so every existing `get`/`toJson` caller returns
  them for free.
- `replaceAddresses(handle, householdId, List<Address>)` — one
  `DELETE FROM household_address WHERE household_id = ?` then a reinsert
  per row. Because nothing has a foreign key **to** `household_address`,
  churning `household_address_id` on every save is harmless (unlike the
  payment ledger). **Preferred is normalised server-side** to exactly one
  row when any exist: the first row flagged `preferred` wins, else the
  first row — so the type-blind readers always have a deterministic pick.
  An empty list clears all rows (the way to drop a bad address).
- `addPostalAddress` (CR-007) is left untouched; it remains the approval
  path's writer. `household_address` now has this CR as a **second
  writer** — the CLAUDE.md "first writer" note is updated.

`AdminHouseholdsResource` (`@RolesAllowed({"admin","manager"})` already —
register maintenance is manager territory per CR-024)

- `toJson(Household)` adds the `addresses` array.
- New `PUT /api/admin/households/{id}/addresses` — parses `addresses[]`,
  validates each (**type** in {POSTAL, RESIDENTIAL}; **line1**
  non-blank and required; other fields optional), wholesale-replaces, and
  returns the updated household. A bad row is a **400 with nothing
  written** (the whole PUT is one transaction); an unknown household is
  404. This matches the resource's existing validate-then-write shape.

### Importer

`ImportService.insertAddress`: address type `'POSTAL'` → `'RESIDENTIAL'`.
No other change — line1-present-⇒-write and preferred=true are unchanged.

### Reader behaviour (unchanged, documented)

The two readers stay type-blind (preferred-else-newest). After this CR:

- Imported households → their **RESIDENTIAL** address is preferred, so
  the register/card show it (appropriate — the register records where a
  member lives).
- CR-007-approved households → their **POSTAL** address is preferred
  (unchanged).
- An admin who adds/edits addresses controls which one is preferred, and
  therefore which one the register/card/receipt use. Changing the readers
  to prefer POSTAL for mailing specifically is **out of scope** (no
  request for it; the preferred flag is the control).

### Client (`households.html` + `admin.js`)

- The `householdDetail` dialog gains an **Addresses** sub-section (after
  the member list, before communication preferences). It renders one
  editable block per address row — type `<select>`, line1/line2/
  locality/state/postcode/country inputs, a **preferred** radio — plus
  **Add address** and **Save addresses** buttons.
- `openHousehold` populates the rows from the GET's `addresses`.
- **Save addresses** gathers the rows and PUTs them; the server's 400 is
  surfaced verbatim (the CR-018/register pattern). Wholesale-replace: what
  is on screen at save is the new set.
- Guarded by the presence of its markup (`renderAddresses` no-ops when the
  section is absent), like every other CR-022 presence-gated renderer.

### Schema

**None.** `household_address` already exists (V1) with every column this
CR uses. No migration.

## Verification

### Matrix (`server/verify-matrix.sh`, new CR28-\* block, self-cleaning)

Against a throwaway household (created + torn down in the block):

1. GET household → `addresses` present and empty on a fresh household.
2. PUT one POSTAL address → 200; GET reflects it; `is_preferred` true.
3. PUT two rows (a POSTAL + a RESIDENTIAL flagged preferred) → 200; GET
   shows both; the RESIDENTIAL is preferred (first in the ordered list).
4. Register-of-members / card address reflects the preferred row.
5. PUT unknown type → 400, nothing written (GET unchanged).
6. PUT blank line1 → 400, nothing written.
7. PUT `[]` → 200, addresses cleared.
8. Roles: guest 403, viewer 403, **manager 200** (opened endpoint),
   admin 200.
9. Importer writes **RESIDENTIAL**: import a household with address
   columns, assert its `household_address.address_type = 'RESIDENTIAL'`
   and `is_preferred`.

The existing `CR3-26b` register-address assertion uses a psql-inserted
POSTAL row (not the import path), so it is unaffected.

### Browser walkthrough (Playwright)

Open a household on `households.html`, add two address rows, mark one
preferred, save; reopen and confirm they persist; edit a field and save;
remove a row and save; confirm the register export shows the preferred
address.

## Results

Implemented on `master` (Opus 4.8), 2026-08-04.

- **Server**: `HouseholdStore.Address` record + `addresses` on `Household`;
  `get` loads them (preferred-first); `replaceAddresses` wholesale-replaces
  with server-side preferred normalisation. `AdminHouseholdsResource` gains
  `addresses` in `toJson` and `PUT /api/admin/households/{id}/addresses`
  (`{"admin","manager"}`). `ImportService.insertAddress` POSTAL →
  RESIDENTIAL. No migration.
- **Client**: Addresses sub-section in the household detail dialog
  (`households.html`) + `renderAddresses`/`addAddressRow`/`saveAddresses`
  in `admin.js`, wired in `wireHouseholds`, populated by `openHousehold`.

**Verification**

- **Matrix** (`verify-matrix.sh`, +20 CR28-\* rows, self-cleaning): the
  full run is **973 / 4**, and all 20 CR28 rows are green — fresh-empty,
  PUT one/two, RESIDENTIAL-preferred ordering, none-preferred
  normalisation, bad-type/blank-line1 400s (nothing written), empty-clear,
  404, guest/viewer 403 + manager/admin 200, and the importer writing
  `RESIDENTIAL` preferred. The 4 failures are the **pre-existing,
  change-unrelated flake families** documented across CRs 013–027: one
  UTC-midnight token-expiry comparison (`CR4-01c`, psql vs JVM clock) and
  three Mailpit-abort rows (`CR5-16b/c/d`, which stop the Mailpit container
  mid-run). Both live entirely in the mail/token subsystems this CR does
  not touch; the 63 mail rows that failed when cargo was first started
  without the CR-004/005 mail env all recovered once it was restarted with
  it, confirming the isolation.
- **Browser walkthrough** (`tmp/cr028-fixtures/cr028-walkthrough.js`,
  Playwright): **13 / 0, zero JS errors** — fresh household empty, add two
  addresses, RESIDENTIAL-preferred, save + server persistence, close/reopen
  renders preferred-first, edit a field + save, remove a row + save leaves
  one normalised-preferred survivor.
