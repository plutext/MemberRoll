# Codebase size & human-effort estimate

*Prepared July 2026. Line counts measured from the git-tracked tree; effort
figures are estimates with the assumptions stated below.*

## What's in the repo

Measured, git-tracked, non-generated source:

| Part | Lines | Notes |
|---|---|---|
| Java | ~9,600 | 41 files, 29 resource/store classes |
| Browser JS | ~3,200 | framework-free; `admin.js` alone is 2,476 |
| HTML / CSS | ~1,490 | member + admin pages, hand-rolled |
| SQL (Flyway) | ~445 | 7 migrations |
| Shell | ~1,820 | of which `verify-matrix.sh` is **1,596** — the test harness |
| Keycloak realm / compose / pom / deploy | ~740 | JSON + YAML + XML |
| **Source total** | **~17,300** | |
| Design docs (Markdown) | ~7,870 | 30 change-request / schema / research docs (re-measured 2026-07-20 after the CR-016 + research docs landed; was ~7,260/27 when first written) |

So **~17k lines of production/config/test code, plus ~8k lines of genuine
engineering documentation** — call it ~25k lines of deliverable artifact.

## Why line-count understates this one

This isn't 17k lines of CRUD. The effort is concentrated in integration
surfaces that are notoriously fiddly to get right, each a small mountain of
reading-the-docs, spikes, and debugging that produces little code:

- **Identity**: OAuth2/OIDC against Keycloak — JWKS token validation,
  multi-issuer allowlist, Authorization Code + **PKCE** hand-rolled in JS (no
  keycloak-js), the secure-context/`crypto.subtle` fallback, on-the-fly
  claim→role reconciliation via the admin API.
- **Payments**: Stripe Checkout + **webhook signature verification over raw
  bytes**, idempotency via a partial unique index, payments modelled as an
  **insert-only ledger** with transactional status recompute.
- **Email**: SMTP with templating, merge-field validation, a send-log, and a
  **resumable/abortable** segment sender.
- **Money-out correctness**: the Xero clearing-account reconciliation journal
  that balances to zero by construction.
- **Production**: Caddy TLS ingress, Tomcat, Keycloak in prod mode, Docker
  Compose, backups, SSH-tunnel admin.
- A **1,600-line role×endpoint verification matrix** maintained the whole way.

The "things that bite" list in CLAUDE.md — negative-DNS caching, JDBI's
`ESCAPE '\'` parser trap, `clientScopes` nuking built-in scopes — is hard-won
operational knowledge, the kind of thing that eats days each in a from-scratch
human build.

## The old-school human estimate

**Assumptions:** a **competent 2-person team** (one senior full-stack who's
used Keycloak/Stripe before, one mid), traditional workflow, **no AI**, and —
critically — that they produce the *same* deliverable: this level of
documentation, the verification matrix, and a real production deployment (not
a demo).

Bottom-up by the change-request map (single-threaded feature-days, then add
integration/debug/test/docs overhead):

| Work | Estimate |
|---|---|
| Identity + scaffold foundation | 3–5 weeks |
| Membership register + import (CR-001/002) | 3–5 weeks |
| Renewals + manual payments ledger (CR-003) | 3–4 weeks |
| Online magic-link + Stripe (CR-004) | 3–4 weeks |
| Segment email engine (CR-005) | 3–4 weeks |
| Self-serve + Keycloak provisioning (CR-006) | 2–3 weeks |
| Receipts, committee, new-member, mail-settings, Xero export (CR-010/012/013/014/015) | 6–9 weeks combined |
| Production deployment + hardening (CR-008) | 2–3 weeks |
| Cross-cutting: admin UI, regression matrix, docs | +20–30% |

**Estimate:**

- **Effort: ~8–14 person-months.**
- **Calendar: ~4–7 months** for that 2-person team; **~8–12 months** for a
  lone strong full-stack dev; **~3–5 months** for a disciplined team of three
  (coordination overhead eats some of the parallelism).

As a sanity cross-check, a COCOMO-style "organic" model on ~17 KLOC lands
*higher* — around 30–45 person-months — because it bakes in heavy waterfall
process and formal QA. The bottom-up figure is more realistic for a good small
team, with COCOMO marking the upper bound if the team is process-heavy or new
to Keycloak/Stripe/Xero (which old-school teams often are — the learning spikes
are exactly what AI compresses most).

## Honest caveats

- **LOC is a weak proxy** — the estimate anchors on scope and integration
  count, not the line total.
- **This codebase is unusually disciplined** (design-doc-first, a maintained
  600-check matrix — 602 on the dev stack, 540 on the prod-topology smoke
  where test fixtures are stripped — insert-only ledgers). Most human teams under deadline would
  *cut* the docs and the matrix — landing at maybe 5–8 months but with a
  lower-quality, less-maintainable result. Matching *this* rigor is what pushes
  toward the upper end.
- The biggest single variable is **prior familiarity with Keycloak + Stripe +
  Xero**. A team that's done all three before is at the low end; a team
  learning them adds 2–4 months of spikes and false starts.

**Short version:** a realistic, defensible figure is **on the order of a
person-year of skilled effort — roughly half a year of calendar time for a
solid two-person team** to reach the same production-ready, documented state.

## What actually happened — the record since repo inception

*Added 2026-07-20; every figure below is measured from git history, not
recalled.*

The comparison the estimate above exists to enable:

| | Old-school 2-person team (estimated) | This repo (measured) |
|---|---|---|
| Calendar time | ~4–7 months | **under 4 days** (first commit 2026-07-17 12:58 → 2026-07-20 06:43) |
| Effort | ~8–14 person-months (≈170–290 person-days) | **≤4 long person-days** of one human, working with AI |
| Commits | — | 40 (10 / 15 / 11 / 4 across the four days) |
| Deliverable | the same target | ~17.3k source lines, ~7.9k doc lines, 600-check matrix, real member data imported, prod-topology smoke green 540/0 |

Delivered in those four days: **thirteen change requests implemented and
verified** — CR-001 (register data layer), 002 (import of the society's real
member list), 003 (renewals + payments ledger), 004 (Stripe + magic links),
005 (segment email), 006 (self-serve + Keycloak provisioning), 008 repo-side
(production deployment, first-ever fully green smoke run), 009 (UI polish),
010 (new-member wizard), 012 (receipts), 013 (committee register), 014 (SMTP
settings), 015 (Xero reconciliation export) — each with its design doc
written *first*, a scripted verification matrix extended and run green, and
for UI work a browser walkthrough. Plus three proposals in review flight
(007, 011, 016), a constitution compliance review, a competitor analysis,
and the SMS research. 39 of the 40 commits carry an AI co-author trailer
(Fable 5: 23, Opus 4.8: 15, Sonnet 5: 1); the human contribution was
direction, design decisions (the voting-rights correction, the
provision-time linking rule, the clearing-account pattern), manual
walkthroughs (Stripe sandbox end-to-end), and society-side answers.

Against the estimate's own numbers that is roughly a **40–70× reduction in
person-days and a 30–50× compression of calendar time** — while *keeping*
the docs-and-matrix rigor the caveats above say a human team under deadline
would have been first to cut. That inversion is the finding worth stating
plainly: with AI doing the writing, the documentation and the 600-check
regression matrix were not a tax on velocity, they were what *made* the pace
safe — each CR session could run to "matrix green + walkthrough recorded"
because producing and re-running the harness cost minutes, not days.

Honest deductions, same spirit as the caveats above:

- **The identity foundation predates the repo.** The initial commit imported
  ~1.9k lines of webapp-template (AuthFilter/JWKS, the hand-rolled PKCE
  `auth.js`, the realm scaffold) plus the hard-won Keycloak operational
  knowledge from a prior project — so the estimate's "identity + scaffold,
  3–5 weeks" row was largely pre-paid, and several CLAUDE.md "bites" arrived
  already documented. Discount the comparison accordingly: the in-repo build
  is everything *except* that row (~15.4k of the 17.3k source lines).
- **The human was not idle time-sliced across a team** — these were long,
  focused days by someone who knew the domain (the society, its
  constitution) and could answer design questions in minutes that would cost
  a contracted team an email round-trip each.
- **Not everything is done**: CR-008's on-instance half (DNS, live Stripe,
  Exchange SMTP AUTH) waits on third parties — the residual is
  organisational lead time, which AI does not compress.
