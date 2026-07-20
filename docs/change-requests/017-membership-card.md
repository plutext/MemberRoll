# CR 017: Membership card — from self-serve, by email, or printed

Status: IMPLEMENTED + VERIFIED (2026-07-20)

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

## Implementation notes (for the build session)

This doc + CLAUDE.md are intended to be sufficient; nothing else needs
reading first. Points the design hinges on:

- **Baseline the FULL matrix before writing any code.** Run
  `server/verify-matrix.sh` against the running dev stack and record
  the pass/fail count. Known pre-existing/environmental failures that
  are NOT yours to chase: `27b` (Keycloak user-listing caps at 50 in a
  long-lived dev stack), `CR10-04g2`/`CR10-12c` (UTC-vs-local
  current_date equality during the daily offset window), and
  occasionally `CR5-16b/c/d` (Mailpit container abort/resume timing).
  The exit criterion is: same failures as your baseline, plus every
  CR17-* row green — and run the CR17 block twice to prove the
  fixtures self-clean.
- **The `Mail` hazard**: the no-attachment path must stay byte-for-byte
  the current single-part message. The proof is the existing mail rows
  (CR4 receipts, CR5 sends, CR12, CR14) green against your build — if
  any mail-body assertion starts failing, the overload leaked into the
  plain path.
- **The bearer-auth hazard**: `<img src="/api/...">` sends no
  Authorization header → 401. Every image use (page `<img>`, download
  link, print pop-up) hangs off ONE blob URL from an authenticated
  `fetch`; `window.print()` only after the image's `load` event.
- Mechanics: restart cargo after Java changes (and after any
  `mvn clean` — see the CLAUDE.md bite); walkthrough script under
  `tmp/cr017-fixtures/` (gitignored), run per the Playwright recipe in
  the repo memory / prior CR scripts (`tmp/cr006-fixtures/` is the
  template); SMTP CRLF canonicalisation applies if asserting mail body
  text (the CR-12 gotcha) — attachment assertions via the Mailpit API
  (`/api/v1/message/{id}` lists `Attachments` with
  filename/content-type).
- When reality diverges from this doc, update the doc (dated), then
  record Results + close out README/CLAUDE.md/ROADMAP.

## Results

Implemented as designed (2026-07-20, Opus 4.8). The pieces:

- `Cards` — the one renderer. `compose(handle, membershipId, personId)` is the
  gate: one SQL joining `membership` (status ACTIVE) × current
  MEMBER-relationship `household_person` × `person` × period × type, empty for
  anything else. Reading current household composition (not the membership
  snapshot) means a PARTNER added after creation is still refused — the
  MEMBER-only rule holds against live state. `png()` renders 1012 × 638 RGB via
  Java2D; `toJson()` is the assertable companion (`name`, `typeName`,
  `periodName`, `validTo` = ISO end_date, `validToText` = "31 August 2026",
  `memberNo` = person id, `filename`, `mailEnabled`); `subject`/`emailBody`/
  `attachment` back the sends. Fonts: both `DejaVuSans.ttf` and
  `DejaVuSans-Bold.ttf` are bundled under `card/` (+ the licence), loaded with
  `Font.createFont` and cached; the optional `card/logo.png` slot is read once,
  present-or-absent, and rendered text-only when absent.
- `Mail` gained the `Attachment(filename, contentType, bytes)` record and the
  `sendAsync(to, subject, body, attachment)` / `send(…, attachment)` overloads.
  The no-attachment path is a straight `message.setText` (unchanged); an
  attachment switches to multipart/mixed (text part + `ByteArrayDataSource`
  file part). Resolution/timeouts/scrub untouched.
- `MeResource`: `GET card` (PNG, `Cache-Control: no-store`), `GET card/info`
  (fields + `emailTo` = caller's primary email), `POST card/email` (to the
  caller's own address only — no `to` param). All 404 via `Cards.compose` when
  the caller is not a current MEMBER of the ACTIVE membership.
- `AdminMembershipsResource`: `GET card/{personId}`, `.../info` (+ `defaultTo`),
  `POST .../email` (`{to?}` → `defaultTo` → 400). Same gate.
- Member page (`web/`): a card section per ACTIVE membership — the blob-fetched
  `<img>`, Download (blob + period-stamped filename), Print (bare pop-up, card
  only, crop marks, `print()` after the image `load`), Email me my card
  (shown per `mailEnabled`/`emailTo`). Admin dialog (`admin/`): a per-person
  row list with a **Card…** button on MEMBER rows of an ACTIVE membership,
  opening a Receipt…-shaped dialog (blob preview / Print / Download / Email).

**The two hazards, both proven clean:**

- *Mail no-attachment path unchanged* — every existing mail row stayed green:
  CR4 receipt (`CR4-19`), CR5 sends + headers (`CR5-11..17`), CR12 receipt
  body-equality (`CR12-06c`) and override (`CR12-07`), CR14 test-send. If the
  overload had leaked into the plain path, `CR12-06c` (mail body byte-equals
  the receipt text) would have broken first. It didn't.
- *Bearer-auth image fetch* — the walkthrough's `B1` asserts the member page's
  `<img>` decoded (`naturalWidth > 0`) from a `blob:` URL, and `B3` that the
  Print pop-up prints only after the image loads. A plain `<img src>` would
  have 401'd.

**Verification.** Baseline full matrix **PASS=603 FAIL=0** (a fully green
baseline this run — even the usually-flaky `27b`/`CR10-*`/`CR5-16*` rows
passed). After implementation, **PASS=649 FAIL=0** — the 46 new `CR17-*` rows
(the 13-case plan, expanded) all green, and re-run identically green
(`PASS=649 FAIL=0` twice), proving the `$$`-suffixed fixtures self-clean. The
`CR17-*` block covers: the guest/noaud 401s and admin 403/401 role gates; the
composed fields incl. `validTo`/`memberNo`/`emailTo`; the PNG's `image/png`
type + `89504e47` magic + >10 kB size; the indistinguishable 404s (foreign
membership, nonexistent id, PENDING, PARTNER, unknown person, unlinked
account); the demote→404→restore→200 standing check; the Mailpit attachment
assertion (one attachment, `membership-card-2025-2026.png`, `image/png`, body
names the society); and the 503 mail-unconfigured flip side.

Browser walkthrough (Playwright, `tmp/cr017-fixtures/cr017-walkthrough.js`)
**PASS=15 FAIL=0**: member page shows no card for a PENDING membership and the
card for the same membership once ACTIVE; the image loads from a blob; Download
carries the blob + `membership-card-2025-2026.png`; the Print pop-up holds only
the card; Email me my card round-trips through Mailpit with the PNG attachment;
the admin dialog shows a Card… button on the MEMBER row and none on the PARTNER
row, its preview loads, and Email sends. The rendered PNG was confirmed legible
at print size (society name band, name, type · period, valid-to, member no.).

### Divergences from the design

- The `card/info` JSON carries `validToText` (the long "31 August 2026" form)
  in addition to `validTo` (the ISO end_date the design named) — the dialogs
  never needed it (the PNG shows the long form), but it is a cheap, honest
  field and keeps the JSON a faithful mirror of the card face.
- Both the regular and bold DejaVu Sans TTFs ship (the design said "a DejaVu
  Sans TTF"); the bold weight is the society name + member name, and bundling
  both is the same licence, so it was worth the ~0.7 MB.

## Follow-ups

- Attach the card to the CR-004 webhook receipt / CR-005 renewal-thanks
  flow once a membership turns ACTIVE (auto-issue; deferred from v1).
- Drop in the society's logo when provided (`card/logo.png`).
- Wallet passes, only if demand materialises — generate from the same
  `Cards` composition.
