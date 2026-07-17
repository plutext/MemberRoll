# CR 000: <title>

Status: PROPOSED | IMPLEMENTED | VERIFIED | CLOSED

## Problem

What is wrong or missing, from the user's point of view. Link the
conversation/issue that raised it.

## Approach

The chosen design, and the alternatives considered with the reason each
was rejected. Call out: new endpoints (method, path, auth rule), new
storage (layout, ownership), realm changes (roles, clients, mappers —
remember the no-clientScopes rule), UI changes.

## Verification plan

The concrete matrix, written BEFORE implementing. For API work that
means every role × endpoint × outcome combination, e.g.:

| # | caller | call | expect |
|---|---|---|---|
| 1 | guest | GET /api/notes | 401 |
| 2 | testuser | PUT /api/notes/a {"title":"t","body":"b"} | 200, stored JSON echoed |
| 3 | testuser | GET /api/notes/a?owner=<other sub> | 403 |
| 4 | testadmin | GET /api/notes/a?owner=<testuser sub> | 200 |

Plus browser walkthrough steps for UI, and the deploy Local smoke when
auth/proxy/compose changed.

## Results

Filled in after running: date, environment, the matrix with actual
outcomes, and anything that surprised.

## Follow-ups / amendments

Dated additions after field feedback.
