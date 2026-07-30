# CR 027: Table pagination (People / Households / Users)

Status: IMPLEMENTED + VERIFIED (2026-07-30)

## Problem

The three register tables cap at 50 rows with no way to reach the rest:

- **people.html** requests `limit=50, offset=0` and prints the server's
  true total ("141 match(es)") — so it shows the first 50 of 141 while
  honestly counting 141, and rows 51–141 are unreachable.
- **households.html** is the identical code path (`limit=50`, offset
  fixed at 0, prints `total`). Same defect; it manifests once there are
  more than 50 households.
- **users.html** requests `max=50, first=0`, and because Keycloak's list
  endpoint returns a bare array with no total, the render shows **no
  count at all** — it silently caps at 50 with no signal there are more.

The servers already support paging: `/admin/people` and
`/admin/households` take `limit`/`offset`, `/admin/users` takes
`first`/`max`. Only the UI (and a users total) is missing. Raising the
client limit is not a fix — the people/households endpoints hard-cap
`limit` at 200, so it would just move the cliff.

## Approach

Prev/Next offset paging with an honest "Showing X–Y of N" label on all
three, page size 50.

- **Client** (`admin.js`): a shared `renderPager(prefix, offset, total)`
  writes the label into `#{prefix}Total` and disables Prev at offset 0 /
  Next at the last page. Each table keeps its offset in a module var
  (`peopleOffset`/`householdsOffset`/`usersOffset`) that only the Prev/Next
  handlers move; a **new search resets it to 0** (the `wireLiveSearch`
  callback wraps the render to zero the offset first, so a keystroke/Go/
  Enter always returns to page 1), while a programmatic refresh (after a
  save/import) preserves the current page. A page that comes back empty
  because rows were deleted under it clamps back to the last valid page.
- **People / Households**: send `offset`, swap the plain total line for
  `renderPager`. The `#{prefix}Total` div becomes a span inside a
  `.pager` div flanked by Prev/Next.
- **Users**: send `first`; fetch the count alongside the list
  (`Promise.all`) from a **new `GET /api/admin/users/count?search=`**.
  New markup adds `#usersTotal` + Prev/Next (users.html had neither).

- **Server**: `KeycloakAdmin.countUsers(search)` calls Keycloak's
  `/users/count` (a bare integer), and `AdminUsersResource.count` exposes
  `{count}` (admin-only, under the class annotation). No correction is
  needed: Keycloak 26 already excludes client service-account users from
  **both** `/users` and `/users/count` (verified live — a 209-user realm
  lists 209 with zero `service-account-*` usernames and counts 209), so
  the count matches the universe the list draws from. (`list()`'s
  `startsWith("service-account-")` skip is pre-existing defensive code
  that never actually fires — row 27c passed before this CR for that
  reason.) An earlier draft of this CR subtracted service accounts on a
  blank search; that was chasing a discrepancy that doesn't exist and was
  dropped.

No schema change; no change to the existing list responses (the users
list stays a bare array, so the matrix's array assertions hold).

## Verification plan

- Matrix: new `CR27-*` rows for `/admin/users/count` — admin gets
  `{count}` (a non-negative int), no service account leaks into the list,
  the count matches the list's universe (exact under the 200 cap), a
  search narrows it, a nonsense search is 0, and the role sweep
  (guest/member/manager 403, noaud 401) since it is admin-only.
- Playwright (`tmp/cr027-fixtures/`): on a table seeded past 50 rows,
  the label reads "Showing 1–50 of N", Next advances to "51–…", Prev
  returns, Prev is disabled on page 1 and Next on the last page, a new
  search resets to page 1, and the users pager total matches the real
  user count (service account excluded).

## Results

Implemented + verified 2026-07-30 (Opus 4.8).

### What changed

- **Server**: `KeycloakAdmin.countUsers(search)`; `AdminUsersResource.count`
  (`GET /api/admin/users/count`, admin-only, `{count}`).
- **Client** (`admin.js`): `PAGE_SIZE`, per-table offset vars, shared
  `renderPager` + `wirePager`; the three render functions send offset/first,
  clamp a paged-past-the-end table, and call `renderPager`; the three wire
  functions reset offset on a new search and wire Prev/Next.
- **Markup/CSS**: `.pager` (Prev · label · Next) on people/households
  (wrapping the existing total span) and users (new); `.pager` CSS.

### Live discovery — no service-account correction needed

An earlier draft subtracted service accounts from the blank count. Probing
the live realm disproved the premise: Keycloak 26 excludes client
service-account users from **both** `/users` and `/users/count` (a
209-user realm lists 209 with zero `service-account-*` usernames and
counts 209; `?search=service-account-` returns 0). So the count already
matches the list's universe; the subtraction was dropped and `list()`'s
`startsWith` skip confirmed as never-firing defensive code.

### curl matrix (`server/verify-matrix.sh`, +10 CR27-* rows)

**PASS=957 FAIL=0 on two consecutive runs** — fully green and
re-runnable. CR27 rows: the admin-only role sweep on `/users/count`
(guest/member/manager 403, noaud 401, admin 200), count is a non-negative
int, no service account in the list, the count matches the list universe
(exact under the 200 cap), a search matches at least its user, a nonsense
search is 0.

**Incidental fix — the long-miscategorised "27b flake".** Row 27b
(list contains `testuser`) had been recorded as a Keycloak flake across
CRs 013–026. It is not flaky: it fetched the list at the default 50-row
limit and asserted `testuser` was present, but on an accumulated realm
(209 users, ordered by username) `testuser` sorts past the first 50 —
a deterministic miss. CR-027's paging work surfaced the true cause;
27b now searches (`?search=testuser`) and is realm-size-independent. This
is why the whole matrix is green here for the first time in many CRs.

### Browser walkthrough (`tmp/cr027-fixtures/cr027-walkthrough.js`, Playwright)

**PASS=21 FAIL=0**, zero JS errors. Seeds >50 people and >50 households,
then on each of People/Households: first page shows 50 rows with
"Showing 1–50 of N", Prev disabled on page 1, Next advances to "51–…",
Prev returns, and a new search resets to page 1. Users: the pager total
equals the live `/users/count` value, the first page is 50, Next advances.
