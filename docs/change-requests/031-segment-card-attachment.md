# CR-031 — attach membership cards to a segment email

## Problem

We want to email the life members (and, in general, any segment) a message
**with their membership card attached** — e.g. "As a life member, here is your
2026-27 card." Neither existing path does this:

- **CR-005 segment/template email** composes a body once and sends it to a
  resolved segment (with the send log, pacing, abort/resume), but it is a
  **single-part message** — `EmailStore.startSending` calls
  `Mail.send(to, subject, body)` (`EmailStore.java:454`). There is no way to
  attach anything.

- **CR-017 card email** attaches the card, but only one person at a time: the
  admin Renewals **Card… → Email** dialog and the member's own
  `/api/me/membership/{id}/card/email`. It also sends a **fixed** body
  (`Cards.emailBody`) with no covering note. Emailing a dozen life members this
  way means a dozen dialog trips with no message control, no log, and no pacing.

- **CR-030 auto-send** attaches the card off a payment, but life members are
  zero-due and never pay online, so it never fires for them.

## Objective

Add an **"attach each recipient's membership card"** option to a CR-005 segment
send. When set, every recipient message carries the membership cards for the
MEMBER-relationship people at that recipient's address — composed per recipient
at send time, riding the *same* body/footer/merge-field/log/pacing/resume
machinery the segment send already has. This is an **amendment to CR-005**, not
a parallel endpoint.

## Recipient model — the couples decision

CR-005 dedups a household's MEMBER people to **one email per address** (couples
share one message; the primary contact, else the lowest person id, wins
attribution). The card, however, is **per person** (own name, own `member_no`).
So a shared-address message must attach **every** MEMBER card at that address:

- **SINGLE membership** → one MEMBER → one card on the one email.
- **HOUSEHOLD membership, two adults sharing an address** → one email carrying
  **both** cards. This is the case the decision targets: the couple gets one
  message, each with their own card attached.
- A MEMBER at that address who resolved to **POST/NONE** (opted out of email) is
  a `SKIPPED_*` row, not part of this recipient — their card is **not** attached
  (comms preference is respected; the set attached is exactly the EMAIL-resolved
  people who deduped into this address, re-derived at send time the same way
  `resolveSegment` grouped them).

So the attached set for a recipient = the MEMBER people of that membership whose
delivery method resolves to EMAIL **and** whose primary email equals the
recipient's address — the same grouping that produced the recipient row.

## Design

### 1. `Mail` — a multi-attachment path (the only new mail capability)

Today `Mail.doSend(settings, to, subject, body, Attachment attachment)` builds a
single filePart when `attachment != null` and a single-part message when it is
null (`Mail.java:311-378`). Generalise to a **list**:

- `doSend(..., List<Attachment> attachments)`: `null`/empty → the **existing
  single-part message, byte-for-byte** (the CR-004/005/012 invariant — the
  branch is unchanged, just reached with an empty list); non-empty →
  `multipart/mixed` with the text part plus **one filePart per attachment**.
- Add `send(to, subject, body, List<Attachment>)` and
  `sendAsync(to, subject, body, List<Attachment>)`. Keep the existing
  single-`Attachment` overloads (`Mail.java:265,284`) delegating to the list
  version via `List.of(attachment)` — so every current caller (CR-012 receipt,
  CR-017/030 single card) produces an identical one-filePart multipart, and the
  no-attachment callers produce an identical single-part message. **No behaviour
  change for any existing send**; the whole prior mail suite must stay green with
  the list plumbing in place (the CR-014/017 proof-by-regression pattern).

The CR-021 sandbox redirect, Reply-To, and the resolve-per-send path are all
inside `doSend` and untouched — an attach-card send is redirected and prefixed
in the sandbox exactly like any other.

### 2. `email_send.attach_card` — the send flag (V12)

```sql
ALTER TABLE email_send ADD COLUMN attach_card boolean NOT NULL DEFAULT false;
```

A send parameter, not part of the subject/body snapshot — one column, defaulting
false so every existing row and the whole CR-005 flow is unchanged. Read back by
`startSending` and `resume` (so a resumed attach-card send still attaches).

### 3. `email_send_recipient` status `NO_CARD` (V12)

A recipient whose membership yields **no composable card** (e.g. the segment
status filter was not ACTIVE — `Cards.compose` is ACTIVE-only) must not receive a
"here is your card" email with nothing attached. Add a bookkeeping status,
mirroring `NO_EMAIL`:

```sql
ALTER TABLE email_send_recipient DROP CONSTRAINT email_send_recipient_status_check;
ALTER TABLE email_send_recipient ADD CONSTRAINT email_send_recipient_status_check
    CHECK (status IN ('PENDING','SENT','FAILED','SKIPPED_POST','SKIPPED_NONE','NO_EMAIL','NO_CARD'));
```

`NO_CARD` rows keep their `email` (unlike `NO_EMAIL`), so the existing
`CHECK ((status = 'NO_EMAIL') = (email IS NULL))` is unaffected. It only ever
occurs on an attach-card send.

### 4. `EmailStore.startSending` — gather cards per recipient

After the body/subject render (`EmailStore.java:428-444`), when the send's
`attach_card` is set:

```java
List<Mail.Attachment> cards = new ArrayList<>();
for (long pid : Cards.memberPersonIds(h, next.membershipId())) {          // CR-030 query: ACTIVE membership × current MEMBER
    if (next.email().equals(CardsRecipientEmail(h, pid))                   // primary email == this address
            && "EMAIL".equals(CommunicationPreferenceStore.resolve(       // preference-respecting (mirrors resolveSegment)
                    h, pid, householdOf(next.membershipId()), snap.communicationType()))) {
        Cards.compose(h, next.membershipId(), pid)
             .ifPresent(c -> cards.add(Cards.attachment(c)));
    }
}
if (cards.isEmpty()) { markResult(h, next.recipientId(), "NO_CARD"); continue; }   // don't send an empty-card message
boolean ok = Mail.send(next.email(), prepared.subject(), prepared.body(), cards);
```

Reuse is deliberate and total: `Cards.memberPersonIds` (CR-030),
`Cards.primaryEmail`, `Cards.compose`, `Cards.attachment`, and
`CommunicationPreferenceStore.resolve` all already exist — this is a gather loop
over them, not new rendering. The card is the same document as every other card
surface (the CR-012/017 one-renderer discipline). The non-attach path
(`attach_card` false) is the **unchanged** `Mail.send(email, subject, body)`
call, so a plain segment send is byte-identical to today.

The 5-consecutive-failure ABORT, pacing (`sendDelayMs`), and mint-per-recipient
`{{payLink}}` all sit around this line unchanged and apply to attach-card sends
for free (pacing matters more here — cards are larger payloads).

### 5. API — `attachCard` on `POST /api/admin/email/sends`

`AdminEmailResource.createSend` (`AdminEmailResource.java:211`) reads
`attachCard` (default false) from the request, validates it as a boolean, and
passes it through `EmailStore.createSend` onto the new column. `preview`
(`/api/admin/email/preview`) is **unchanged** — the recipient set is identical;
attach-card changes only what each recipient carries. `resume` reads the stored
flag. Opened to `{"admin","manager"}` exactly as the rest of the segment surface
(CR-024) — no annotation change (it is already a manager-usable resource).

### 6. UI — a checkbox on the compose form

`admin/index.html` (or wherever the segment compose form lives) gains an **"Attach
each recipient's membership card"** checkbox wired into the `sends` POST body. A
muted hint states that **cards attach only for ACTIVE memberships** — so if the
admin pairs it with a non-ACTIVE status filter, the send log's `NO_CARD` rows
explain why nothing went. (Optional polish, not required for v1: have `preview`
also report a card count so the admin sees "12 cards will attach" before sending;
deferred because it means composing in preview.)

## What is deliberately *not* changing

- **No new endpoint** — the whole feature rides the CR-005 segment send.
- **No change to the body/merge-field machinery** — the covering note is an
  ordinary template body; `{{givenName}}` still greets the attributed person.
- **No change to receipt/auto-send/card-dialog paths** — the single-`Attachment`
  overloads are preserved and now delegate, proven byte-identical by regression.
- **The CR-017 card gate is authoritative** — cards come only from
  `Cards.compose` (ACTIVE + current MEMBER-relationship, live household), never a
  snapshot; a non-carding recipient is `NO_CARD`, never an empty attachment.

## Verification plan

Scripted curl matrix against the dev stack (extend `verify-matrix.sh` with
self-cleaning `CR31-*` rows), asserting HTTP status/JSON and inspecting **Mailpit**
for attachment presence/count:

1. **SINGLE, ACTIVE segment, attachCard=true** → send row RUNNING→COMPLETE;
   Mailpit's delivered message has exactly **one** attachment (PNG, card
   filename); recipient row `SENT`.
2. **HOUSEHOLD couple sharing an address, attachCard=true** → **one** message to
   the shared address with **two** attachments (both members' cards); one `SENT`
   recipient row for the address.
3. **A POST-preference MEMBER at that shared address** → their card is **not**
   attached (message still has only the EMAIL members' cards); they remain a
   `SKIPPED_POST` row.
4. **Non-ACTIVE (e.g. LAPSED) segment, attachCard=true** → recipient rows
   `NO_CARD`, **no** message delivered.
5. **attachCard=false** (or absent) → byte-for-byte the existing CR-005
   single-part send (regression — the whole prior mail suite stays green).
6. **Resume** an attach-card send aborted on a dead relay → resumed messages
   still carry cards (flag survives on the send row).
7. Merge-field strict validation, footer inclusion, `{{payLink}}` mint, and
   pacing (`sendDelayMs`) all unaffected (existing CR-005 rows stay green).

Plus a Playwright walkthrough: compose form checkbox present, the ACTIVE-only
hint shown, a real attach-card send to a small ACTIVE segment lands in Mailpit
with the card attached.

## Verification results

Implemented and verified 2026-08-11 (Opus 4.8). V12 applied cleanly against the
long-lived dev DB (`attach_card` default false; the recipient-status CHECK now
admits `NO_CARD`).

- **Matrix (`server/verify-matrix.sh`, +22 `CR31-*` rows, self-cleaning):
  1023/1**, the one failure the pre-existing `CR4-01c` UTC-calendar flake
  (unrelated to mail/cards). New rows reuse the CR-005 fixture — household A
  (Ada+Bert, both MEMBER sharing `$SHARED`) paid into ACTIVE is the couple case,
  household D (Dot) the single case, a new household E the NO_CARD case:
  - attach-card ACTIVE send → 2 SENT, **couple's one message carries 2 card
    attachments**, single carries 1, PARTNER (Cleo) not mailed (`CR31-01…06`);
  - attach-card PENDING_PAYMENT send → 0 SENT, **1 NO_CARD**, E gets no mail
    (`CR31-07…08c`);
  - non-attach send → delivered with **0 attachments** (the byte-for-byte
    no-attachment path regression, `CR31-09…11`);
  - `attach_card` persisted on `email_send` (`CR31-01c`).
  All prior mail rows (CR-004/005/012/017/030) stayed green — the single-part and
  single-attachment paths are unchanged.
- **Browser walkthrough (`tmp/cr031-fixtures/cr031-walkthrough.js`): 11/0** —
  the checkbox and its ACTIVE-only hint toggle, the send POST carries
  `attachCard:true`, the confirm names the card attachment, and an end-to-end UI
  send delivers the couple's two cards on one message to the shared address.

## Rollout note

For the immediate life-members job: turn on the CR-021 mail **sandbox** first
(real-data testing), create an ACTIVE **LIFE**-type segment, write the covering
note as the body, tick **attach card**, and send — pacing at the deployed
2500 ms keeps it under the O365 rate limit. The ~life-member batch is small, so
one send covers them.
