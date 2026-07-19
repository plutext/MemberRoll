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
| Design docs (Markdown) | ~7,260 | 27 change-request / schema docs |

So **~17k lines of production/config/test code, plus ~7k lines of genuine
engineering documentation** — call it ~24k lines of deliverable artifact.

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
  540-check matrix, insert-only ledgers). Most human teams under deadline would
  *cut* the docs and the matrix — landing at maybe 5–8 months but with a
  lower-quality, less-maintainable result. Matching *this* rigor is what pushes
  toward the upper end.
- The biggest single variable is **prior familiarity with Keycloak + Stripe +
  Xero**. A team that's done all three before is at the low end; a team
  learning them adds 2–4 months of spikes and false starts.

**Short version:** a realistic, defensible figure is **on the order of a
person-year of skilled effort — roughly half a year of calendar time for a
solid two-person team** to reach the same production-ready, documented state.
