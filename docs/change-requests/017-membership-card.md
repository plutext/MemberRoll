# CR 017: Membership card — from self-serve, by email, or printed

Status: PROPOSED (2026-07-20)

## Problem

Members want a membership card they can carry or show. On-phone wallet
passes (Apple Wallet / Google Wallet) were considered and rejected —
see Rejected alternatives — as too complex for the membership and an
operational trap for a volunteer society. What fits is something a
member already knows how to keep: an image on their phone, an email
they can refer back to, or a piece of paper they cut a card from.

Society decisions recorded 2026-07-20:

- cards are for **MEMBER-relationship people only** (PARTNER /
  DEPENDANT / OTHER are covered by the membership but get no card —
  the voting-rights composition rule's sibling);
- **no validity-check / QR feature** — the card is a low-stakes
  credential, not a verifiable one;
- card face: society name, person's name, membership type, validity,
  member number;
- a **logo will be provided later** — the renderer ships with the slot
  empty (text-only layout until the asset lands).

## Approach

### One card renderer

The CR-012 lesson applied again: one renderer (working name `Cards`),
several delivery surfaces, so the on-screen copy, the download, the
print pop-up and the emailed attachment are the same document. It
composes from CURRENT register state at request time — never from
anything stored — and renders only for a membership whose status is
**ACTIVE**: a card asserts financial standing, so an unpaid or lapsed
membership simply has no card (and the incentive points at the pay
button). Staleness needs no revocation machinery: the card carries
"Valid to <period end_date>" (the Sep–Aug year), so a saved image
expires visibly on its own.

Card face:

- society name (`Mail.societyName()`), plus the logo when the optional
  classpath resource `card/logo.png` is present — a repo asset, not an
  upload (the fork-distribution model: a fork drops in its own file);
- the person's name — preferred name when set, else given name, plus
  family name (how the person is actually known);
- membership type + period name;
- "Valid to 31 August 2027" (the period's `end_date`);
- "Member no. <person_id>" — the person id is the member number for the
  same reason the payment id is the receipt number: stable and never
  renumbered, where `membership_id` changes every period.

Rendering is a PNG via Java2D (`BufferedImage` + `ImageIO`, JDK-only —
no new dependency), credit-card ratio 85.6 × 54 mm at 300 dpi
(1012 × 638 px) so a printed card is crisp at true size. A headless
server's font set is unreliable, so a DejaVu Sans TTF (Bitstream Vera
licence permits bundling; licence file ships alongside) is loaded with
`Font.createFont` — never trust `new Font("SansSerif")` on the server.

Like `Receipts`, the renderer also has a JSON companion (`toJson`: the
composed fields plus `mailEnabled` and the default address) — the
assertable surface for the matrix and the dialogs, so the PNG itself
only ever needs a magic-bytes/size check.

### Member endpoints (MeResource — authenticated, deliberately NOT `@RolesAllowed`)

The CR-006 rule holds: the `person.keycloak_subject` link is the
authority, entitlement re-derived per request. 404 unless the
membership is in the caller's current-MEMBER set (the `canPay`
derivation: a current MEMBER row in the membership's household) AND
the membership is ACTIVE — indistinguishable from "no such membership",
exactly like pay-link, so nothing enumerates.

| method | path | behaviour |
|---|---|---|
| GET | `/api/me/membership/{id}/card` | the PNG (`image/png`, `Cache-Control: no-store`). |
| GET | `/api/me/membership/{id}/card/info` | the composed fields + `mailEnabled` + `emailTo` (the caller's primary register email, null when none) — the page decides which buttons to show from this. |
| POST | `/api/me/membership/{id}/card/email` | email the card to the caller's own primary register email — deliberately no `to` parameter (a member's card goes to the member's address; the server is not an arbitrary-destination mailer). 400 when the person has no email; 503 when `Mail.enabled()` is false (the checkout mirror). Stateless. |

### Admin endpoints (AdminMembershipsResource, `@RolesAllowed("admin")`)

The admin path matters: most members will never be Keycloak-linked, so
the admin dialog is likely the primary card-issuing surface. Same
composition gate: 404 unless `{personId}` is a current
MEMBER-relationship member of the ACTIVE membership's household.

| method | path | behaviour |
|---|---|---|
| GET | `/api/admin/memberships/{id}/card/{personId}` | the PNG. |
| GET | `/api/admin/memberships/{id}/card/{personId}/info` | fields + `mailEnabled` + `defaultTo` (the person's primary email, null when none — a couple's shared-address partner gets null and the admin types it, the CR-012 pattern). |
| POST | `/api/admin/memberships/{id}/card/{personId}/email` | body `{to?}`; absent → `defaultTo`, 400 when neither; 503 mail off. Stateless. |

### Mail: attachment support

`Mail` is plain-text only today (`message.setText`). One overload —
`sendAsync(to, subject, body, attachment)` with a small
`Attachment(filename, contentType, bytes)` record — builds a
multipart/mixed message (text part + attachment part). The
no-attachment path stays byte-for-byte the current single-part message
(proven by the existing CR-004/005/012 mail rows staying green), and
resolution/timeouts/scrub are untouched. Attachment, not inline-CID:
attachments survive image-blocking mail clients, and "a file you can
save or print" is the product.

### UI

**Member page** (`web/`): each ACTIVE membership gains a card section —
the card image shown on the page, **Download** (save to
gallery/Downloads on phones), **Print** and **Email me my card**
(hidden/hinted per `info`'s `mailEnabled`/`emailTo`, the CR-005 banner
pattern).

The bearer-auth bite, recorded here because it shapes the whole page
half: static pages authenticate with bearer tokens, so a plain
`<img src="/api/me/...">` sends NO Authorization header and gets a 401.
The page must `fetch` the PNG with the header, wrap it in a blob URL,
and hang the `<img>`, the `<a download="membership-card-<period>.png">`
and the print pop-up off that one blob. Same-origin pop-ups can use the
opener's blob URL; `window.print()` only after the image's `load` event
(printing before decode yields a blank card).

**Print layout** (member and admin, the CR-012 bare-pop-up pattern): a
window containing only the card image, CSS-sized to its true
`width: 85.6mm` with crop marks, then `window.print()` — no print CSS
against the host page.

**Admin membership detail dialog**: a **Card…** button per person row,
shown only for MEMBER-relationship rows of an ACTIVE membership,
opening a dialog mirroring Receipt… — preview image (blob-fetched),
Print, Download, Email prefilled with `defaultTo`.

### Rejected alternatives

- **Wallet passes** — two entirely separate vendor integrations (Apple:
  paid developer account + Pass Type ID certificate + PKCS#7-signed
  `.pkpass`; Google: Cloud project + approved Issuer + JWT-signed
  objects), an annually-expiring Apple certificate (a silent time bomb
  for a volunteer society — the CR-014 "key to lose" argument, twice),
  and a membership that would need help using them. Nothing here
  forecloses generating a pass from the same card data later.
- **Client-side canvas rendering** — the email path needs server
  rendering anyway; one renderer beats two ("same document" would
  otherwise be unprovable).
- **PDF** — a dependency and template engine for what the browser's
  print dialog does at this scale (the CR-012 argument).
- **SVG** — lighter to generate, but Outlook won't render it and phones
  save it badly; PNG works everywhere.
- **Auto-attach the card to payment/renewal emails** — kept out of v1
  (on-demand first, the receipts "if requested" spirit); the obvious
  follow-up seam once the card exists.
- **An admin logo-upload page** (`app_setting` blob, CR-014 style) —
  an upload surface is machinery without a second tenant to justify it;
  a file in the repo fits how forks are distributed.
- **Cards for PARTNER/DEPENDANT people** — society decision above.

### Config

None new. No schema change, no realm change. New bundled resources:
the DejaVu Sans TTF (+ licence file) and the empty `card/logo.png`
slot.

## Verification plan

Extend verify-matrix.sh (CR17-* rows, self-cleaning fixtures): linked
person L (the CR-006 provisioned account) with ACTIVE membership A;
a PENDING membership P in another household; a PARTNER-relationship
person R sharing A's household.

| # | caller | call | expect |
|---|---|---|---|
| 1 | guest / noaud | GET me card, info; POST email | 401 each |
| 2 | linked L | GET `/api/me/membership/{A}/card/info` | 200; name, type, period, `validTo` = period end_date, `memberNo` = person id, `mailEnabled` true, `emailTo` = L's primary email |
| 3 | linked L | GET `.../card` | 200, `Content-Type: image/png`, body starts with the PNG magic bytes, length > 10 kB |
| 4 | linked L | GET/POST against P (not theirs) and against a nonexistent id | 404, indistinguishable |
| 5 | linked L, A demoted to PENDING (fixture flip, restored after) | GET card/info | 404 — no card without financial standing |
| 6 | linked L | POST `.../card/email` | 202; Mailpit: one message to `emailTo`, attachment named `membership-card-<period>.png`, content-type `image/png`; body mentions the society name |
| 7 | unlinked account (testviewer) | GET card/info | 404 |
| 8 | guest / testuser | admin card endpoints | 401 / 403 |
| 9 | testadmin | GET `/api/admin/memberships/{A}/card/{L}/info` + `/card` | 200; same fields; PNG magic bytes |
| 10 | testadmin | GET `.../card/{R}` (PARTNER) | 404 — MEMBER-only |
| 11 | testadmin | GET card for P (PENDING) / unknown membership / unknown person | 404 each |
| 12 | testadmin | POST email no body (defaultTo present) / `{to}` override / no default and no `to` | 202 to default / 202 to override / 400 |
| 13 | — | mail unconfigured (conditional row, CR4-09u pattern) | POST email answers 503 |

Regression: every existing mail row (CR4 receipts, CR5 sends, CR12,
CR14) stays green — proves the no-attachment `Mail` path unchanged.

Browser walkthrough (Playwright): member page shows the card image for
the ACTIVE membership (and none for a PENDING one); Download link
carries the blob + filename; Print pop-up contains only the card;
Email round-trips via Mailpit with the attachment; admin dialog Card…
preview/Print/Email, and no Card… button on a PARTNER row. Confirm the
rendered PNG visually once (name/type/dates legible at print size).

## Results

(to be recorded at implementation)

## Follow-ups

- Attach the card to the CR-004 webhook receipt / CR-005 renewal-thanks
  flow once a membership turns ACTIVE (auto-issue; deferred from v1).
- Drop in the society's logo when provided (`card/logo.png`).
- Wallet passes, only if demand materialises — generate from the same
  `Cards` composition.
