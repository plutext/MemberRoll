# SMS messaging — provider options

*Prepared July 2026. Research to inform whether/how to add SMS as an
alternative delivery method to email. Prices change frequently and were
re-checked July 2026 — treat as indicative and re-confirm before committing.*

At our scale (~150 members, low volume) **price is almost irrelevant** — the
real decisions are Australian compliance and how cleanly a provider's API fits
the existing `Mail`/`CommunicationPreferenceStore` architecture.

## Australian SMS pricing (pay-as-you-go, per message)

| Provider | Per SMS (AUD) | Monthly | Dedicated number | API | Notes |
|---|---|---|---|---|---|
| **Mobile Message** 🇦🇺 | ~1.6c | none | free | REST, batch 10k | Cheapest; pay-as-you-go credits |
| **160.com.au** 🇦🇺 | ~1.9c | none | — | REST | Direct carrier routing |
| **Cellcast** 🇦🇺 | ~2.8–3c | opt. $18/mo for number | paid | basic REST | Cheapest at high volume (not our case) |
| **SMS Broadcast** 🇦🇺 | ~3.7c | none | — | simple REST | Budget, minimal |
| **ClickSend** 🇦🇺 | ~5.7c | none | cheap | **excellent REST + docs** | Also does email/post — could consolidate channels |
| **MessageMedia (Sinch)** 🇦🇺 | ~6c | **$45/mo** | — | good REST | Subscription — overkill for 150 members |
| **Twilio** 🌐 | US$0.0515 (~8c) /segment | US$8.25/mo per number (~A$150/yr) | paid | **best-in-class API/SDK** | Alphanumeric sender free but one-way; you self-manage AU registration |

**Real cost to us:** ~150 members × a few renewal nudges + confirmations ≈
600–1,000 SMS/year. That is **~$10–16/year at Mobile Message**, or ~$200/year
on Twilio (the number rental, not the messages, dominates there). Either way
it is a rounding error — so **optimise for compliance and integration, not
price**.

## The compliance finding that actually matters

**Australia's SMS Sender ID Register (ACMA) is now in force** — mandatory since
**1 July 2026** (registration deadline was 15 May 2026). If we send with a
**branded alphanumeric sender** (e.g. "YASSHIST"), it **must be registered** via
our messaging provider, or recipients' phones flag the messages as
**"Unverified"** and group them as suspected scam. Implications:

- **Use a dedicated virtual mobile number instead of an alpha sender** and we
  sidestep registration entirely — *and* it enables two-way replies, which we
  want for opt-out (**reply STOP**) handling. For a small society this is the
  pragmatic choice. Most AU providers (Mobile Message, ClickSend) include a
  free/cheap number and guide you through any registration; Twilio makes you do
  it yourself.
- **Spam Act 2003**: renewal reminders/receipts are largely transactional, but
  consent + a working opt-out is still expected. Our existing
  `CommunicationPreferenceStore` (person → household → default) already models
  per-person delivery method — SMS opt-out slots into it.

## How well it fits what we've built

SMS is a **natural extension, not a rebuild** — the architecture already
anticipates it:

- **`CommunicationPreferenceStore`** resolves a delivery method per person and
  defaults to EMAIL. Adding `SMS` as an alternative method is exactly the seam
  it was designed for; `EmailStore.resolveSegment` already does the
  method-resolution step.
- **The `Mail` pattern** (CR-014: PAGE → ENV → NONE resolve-per-send, admin
  settings page, never-logged secret, `enabled()` gating) is a ready-made
  template for an `Sms` sender + an admin SMS-settings page.
- **The AU providers are a plain HTTP POST with an API key** — no SDK needed,
  matching our "plain JDK, no framework" ethos (`java.net.http.HttpClient`).
  Twilio has a Java SDK but that is a new dependency; the AU providers do not
  need one.
- **Magic-link renewals are the killer use case.** SMS can't carry PDF receipts
  or rich formatting, but it is ideal for a short, high-open-rate nudge carrying
  the CR-004 pay link: *"Your YHS membership is due — renew: <link>"*. Budget
  ~2 segments per message (a pay URL pushes past the 160-char single-segment
  limit). Receipts and detailed comms stay on email.

## Recommendation

- **Best fit: ClickSend or Mobile Message** (both 🇦🇺, REST, no monthly,
  dedicated number). **Mobile Message** wins on cost + free number; **ClickSend**
  wins on documentation/support and is multi-channel (email + post too, if we
  ever want one vendor for everything).
- **Twilio** only if we specifically value its best-in-class API/reliability and
  might expand later — ~5× the per-message cost plus number rental, though still
  immaterial in absolute dollars.
- **Skip MessageMedia's $45/mo** — a subscription we can't justify at this volume.
- **Use a dedicated virtual number, not an alpha sender ID**, to avoid the
  Sender ID Register and get free STOP handling.

## One honest caveat

SMS is a **complement to email, not a replacement**, for this society
specifically: our members skew older and we likely don't have a mobile number on
file for all of them. SMS's real value is as a **high-conversion renewal-nudge
channel** for members who *have* a mobile, layered on top of email — exactly what
the "alternative delivery method" framing gives us. Worth checking the register's
mobile-number coverage before committing, since that determines how many members
SMS can actually reach.

## Sources

- [SMS Comparison AU — gateway prices](https://www.smscomparison.com.au/sms-gateway/)
- [Mobile Message — bulk SMS](https://mobilemessage.com.au/bulk-sms)
- [ClickSend — pricing](https://www.clicksend.com/au/pricing/)
- [Twilio — Australia SMS pricing](https://www.twilio.com/en-us/sms/pricing/au)
- [ACMA — SMS Sender ID Register](https://www.acma.gov.au/sms-sender-id-register)
- [DLA Piper — Sender ID registration explainer](https://privacymatters.dlapiper.com/2026/01/australia-return-to-sender-id-businesses-must-register-branded-identifiers-used-in-australian-sms-messages/)
