---
title: "Membership software — committee briefing"
subtitle: "Our in-house system weighed against cheaper products and open-source alternatives"
date: "July 2026"
---

# Membership software: our system vs. what we could have bought

**Prepared for:** the Committee  **·**  **Scenario:** ~150 members, ~\$30 renewals  **·**  **Prices current:** July 2026

A plain-English comparison of our in-house membership and renewals system with the cheaper products Australian associations commonly use — and the free open-source alternatives — costed for a society of about 150 members.

---

## The bottom line

The cheap headline options don't really fit us. **TidyHQ's free tier is capped at 100 emails a month** — not enough to email a 150-member society even once — so in practice we'd be on its paid plan. The genuinely usable products cluster around **A\$540 to A\$2,000 a year**, several taking a percentage cut of every renewal on top.

**Xero integration is not a point of difference** — several products have it, the best of them New Zealand-made (Hello Club, Friendly Manager, ClubHub). What our own system buys us is **fit, ownership and freedom**: it works exactly the way our constitution does, keeps members' data in our hands with no per-member fees or transaction rake, runs on little more than hosting — and, crucially, **we can keep extending it as new needs come up** rather than waiting on a vendor's roadmap.

---

## 1. What each option would cost us

Annual figures are the subscription for ~150 members, converted to Australian dollars where the vendor prices in US dollars (≈1.55, approximate). The **extra fee per payment** is what gets skimmed off every online payment *on top of* Stripe's normal bank fee — at our size that can matter as much as the subscription.

| Product | Approx. per year | Extra fee per payment | Xero integration | What to know |
|---|---|---|---|---|
| **Our own system** *(in-house)* | Hosting only (modest) | None — just Stripe 1.7% + 30c | **Yes** | Full data ownership, no per-member cost, exact fit to our rules. |
| **TidyHQ** *(🇦🇺 Australian)* | A\$890 *(free tier too limited)* | +1% + 20c (paid plan) | No | Free tier caps at **100 emails/month** — can't email 150 members even once — so we'd need the paid plan. |
| **Join It** *(🇺🇸 US)* | ~A\$540 | **+3.0%** service fee | No | Cheapest sticker price, but the highest cut of each renewal. |
| **Member Jungle** *(🇦🇺 Australian)* | ~A\$1,440 +GST | Stripe only | No | Includes a website & branded app. Mandatory setup fee A\$399–1,499. |
| **Wild Apricot** *(🇨🇦 Canada)* | ~A\$1,490 | **+20%** on sub if using Stripe | No | Bills by *contacts*, not members — lapsed members & event guests count, so cost creeps up. |
| **Hello Club** *(🇳🇿 New Zealand)* | ~A\$1,970 | Stripe only | **Yes** | Auto-syncs invoices & payments to Xero. Cheapest tier caps at 100 members, so 150 forces the dearer plan. |
| **Friendly Manager** *(🇳🇿 New Zealand)* | ~A\$1,540 | Stripe only | **Yes** | Free two-way Xero sync of all transactions. The NZ\$139/mo tier covers up to 250 members. |
| **ClubHub** *(🇳🇿 New Zealand)* | ~A\$970 *(≈NZ\$7/member)* | Stripe only | **Yes** | Strong two-way link: member balances become Xero invoices; Xero-reconciled payments flow back. Priced per member. |

*GST extra where noted. Currency conversions approximate (USD ≈1.55, NZD ≈0.92 to AUD). Figures re-checked July 2026.*

---

## 2. The free, open-source route

Two mature open-source systems could, in principle, run our membership for no licence fee. Both are the same kind of thing we built ourselves — self-hosted, data owned, Stripe-capable — which makes them the fairest "did we need to build our own?" comparison.

**Tendenci.** A purpose-built association system on the same technology family as ours (Python/Postgres), free to self-host, with Stripe and recurring renewals. Its managed-hosting option, however, runs ~A\$310+/month — dearer than TidyHQ. The catch with self-hosting is identical to ours: it needs someone technical to keep it running.

**CiviCRM.** The best-known open-source system for non-profits — no member cap, no monthly fee, with renewal reminders and a Stripe add-on. But it rides on top of a WordPress or Drupal website and needs ongoing technical upkeep. Powerful, but a bigger machine than a 150-member society usually wants to feed.

> **The honest point:** if we were starting today with "we want open-source and self-hosted," Tendenci or CiviCRM would deserve a serious trial before writing any code. Both, however, are large generic systems we'd have to configure *down* to fit — where ours already fits — and each still needs a technical hand to keep running, just as ours does.

---

## 3. Why our own system still earns its place

Xero aside, the case for continuing with what we've built rests on things the cheaper products can't give us — and one that only grows in value over time.

- **Built to our constitution.** Member-only voting, AGM-to-AGM committee terms, and no-login renewal links all work the way *our* rules say — not a generic club template we'd bend to fit.
- **Xero on our own terms.** It produces a reconciliation journal that balances against a Stripe clearing account, at no added cost. Xero itself isn't unique to us (the NZ products above have it too) — but ours is tailored to exactly how our treasurer already works.
- **Our data, no metering.** Members' details stay in our hands. No per-member pricing, no "contact count" creep, no 20% surcharge for using our own payment provider.
- **Only the bank's fee.** Renewals carry Stripe's standard 1.7% + 30c and nothing more — no platform taking an extra 1–3% of every membership payment.

**It grows with us — the freedom to add what we need next.** Because we own it, we can keep extending it as new needs surface, on our own timetable and at no per-feature licence cost. A concrete example already within reach: a **members-only area on our website** — protected sign-ins built on the very same secure login system (Keycloak) our membership system already runs, so members would use one account across both. That kind of enhancement simply isn't on offer with a fixed off-the-shelf product; with ours it's a natural next step rather than a wait for a vendor's roadmap.

---

## 4. In short

- **No off-the-shelf product is a clear bargain for us:** TidyHQ's free tier can't send enough email, and the usable plans run A\$540–2,000/year, several skimming a percentage of every renewal.
- **Xero integration isn't the deciding factor** — several products (especially the NZ ones) offer it, so we shouldn't lean on it as our reason.
- **Our system wins on fit, data ownership and the lowest ongoing cost** — hosting only, no per-member fees, no transaction rake.
- **Its real long-term advantage is extensibility:** we can keep shaping it to the society (starting with a members-only web area) instead of paying more each year for someone else's fixed feature set.

---

*Prices and features re-checked July 2026 and change frequently — treat figures as indicative and re-confirm before any decision. Currency conversions are approximate (USD ≈1.55, NZD ≈0.92 to AUD). Sources include the vendors' own pricing pages and Xero App Store listings: TidyHQ, Member Jungle, Hello Club, Friendly Manager, ClubHub, Wild Apricot, Join It, Tendenci and CiviCRM.*
