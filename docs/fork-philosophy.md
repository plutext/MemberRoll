# Fork, don't configure — AI-assisted customisation as the distribution model

*Prepared July 2026. Companion to `membership-software-briefing.md` (the
market context) and `codebase-size-and-effort-estimate.md` (the measured
build record). Status: a working philosophy, adopted; the charter below
governs how forks should be made.*

## The thesis

MemberRoll is open source (Apache 2.0) and single-tenant by design. The
intended way for another organisation to adopt it is **not** to request
config options, and not to wait for a hosted multi-tenant edition. It is:

> Fork the repo. Point a competent AI agent at it and say: *"I would like
> to use this to manage an association. Here is its constitution. Conduct
> a gap analysis against the features of the software, and prepare change
> requests to effect the necessary changes."* Implement the CRs in the
> fork, verified the same way this repo verifies everything.

The result is a customised system that fits the organisation the way this
instance fits the Yass & District Historical Society — built to its
governing document, not bent from a generic template. This supercharges
the ordinary GitHub-fork philosophy: forking has always been *possible*;
capable AI agents make it *feasible* for organisations that don't employ
developers, which is nearly all membership organisations (see the
briefing: the alternative is A$540–2,000/yr for a product you configure
*down* to fit).

## Why this repo, specifically, supports it

The forkable asset is not the ~17k lines of code. It is the **~8k lines
of recorded reasoning** around them:

- `CLAUDE.md` — the architecture in paragraphs, and the "things that
  bite" list: hard-won operational knowledge an agent inherits instead of
  rediscovering.
- Sixteen change-request docs with design rationale, rejected
  alternatives, and dated in-place corrections — the *why* behind every
  structure an agent might otherwise "clean up" into a bug.
- `server/verify-matrix.sh` — a ~600-check role × endpoint matrix. A
  fork's agent runs it green before touching anything and green after,
  which turns "modify software you didn't write" from an act of faith
  into a mechanical proof of non-regression.

An agent pointed at a typical open-source system starts by
reverse-engineering intent from code. An agent pointed at this repo
starts by *reading* intent. The docs-first discipline that looked like
rigor for its own sake turns out to be the product: **the CR pattern is
the customisation interface.**

## The economics: why config options lose

Config options exist because changing code used to be expensive and
changing a dropdown wasn't. Vendors therefore pre-built every anticipated
variation speculatively — and every option multiplies the space of
untested combinations. The result is the generic AMS the briefing
surveyed: powerful, and fitting nobody exactly.

A competent agent collapses the cost asymmetry that justified this. When
a *verified* code change costs roughly what flipping a flag used to,
bespoke-and-tested beats generic-and-configured. The fork carries only
the code paths its organisation actually uses, all of them exercised by
its matrix.

**Where config still earns its place** — the principle, distilled from
the one knob this design deliberately keeps (DGR status: *with fund / no
fund / no DGR*, currently roadmap):

> Config for small, **closed, legally-defined enums** that are orthogonal
> to structure and can change at runtime for a single organisation
> (gaining DGR endorsement changes receipt wording, not the data model —
> and all three values must be tested regardless).
> **Fork for structural divergence** (household vs individual membership,
> a different governing act, a different compliance regime) — where no
> flag is honest and the combinations could never all be tested.

## It has already run once

The proposed workflow is not hypothetical — it happened in this repo on
2026-07-18, applied to this instance's own organisation: the society's
constitution was reviewed against the built system, and the output was
CR-007's clause-3 application constraints, CR-011's four compliance gaps
(register export, suppression flag, under-18 AGM exclusion, documented
suspension), and one correction to an already-built rule (MEMBER-only
voting). "Here is the governing document, find the gaps, write the CRs"
is the same prompt whether the document is a historical society's
constitution or an Electoral Act — see the worked example in
`docs/forks/riverina-state-gap-analysis.md`.

## The charter — rules for a well-made fork

**For the fork's agent (put this in the prompt, or let it find this
file):**

1. Read `CLAUDE.md` first, then the CR docs your change touches. Run
   `server/verify-matrix.sh` green against the dev stack **before**
   changing anything — that's your baseline.
2. Write the gap analysis and CRs **before** code, in the fork's
   `docs/change-requests/`, following the house pattern (approach,
   design, verification plan; results recorded after).
3. Extend the matrix with every new endpoint and behaviour; it must end
   green and re-runnable. UI changes get a browser walkthrough.
4. **Designed-to-change seams** (customise freely): membership types and
   their people-bounds (data, not code), relationship-type semantics,
   communication types and channels, exports and reports, periods and
   fee structures, wording/branding, admin UI pages.
5. **Load-bearing surfaces** (adapt only with the original CR doc open,
   never regenerate from scratch): `AuthFilter`/JWKS validation, the
   PKCE flow in `shared/auth.js`, Stripe webhook signature verification,
   the insert-only payment ledger and its transactional status
   recompute, the Keycloak claim→role reconciliation. These encode
   security and financial-integrity decisions whose *absence* of
   flexibility is the feature.

**Fork mechanics:**

- Keep customisations on a dedicated branch (e.g. `fork/<org>`), one
  CR per commit or commit-group, so the series is legible.
- **Upgrades: rebase the CRs, not the diffs.** To take an upstream
  release (especially security fixes), don't merge textually — have the
  agent *replay* the fork's CR docs onto the new base and re-run the
  matrix. The CR doc, not the diff, is the durable customisation
  artifact; the code is its compilation.
- Watch upstream for security-relevant commits; the replay discipline
  makes taking them cheap.

**Contribution rules:**

- Generally useful → PR upstream. Test: does it touch a seam every
  membership organisation has (a report format, a channel, a payment
  method, an admin affordance)?
- Encodes *your* governing document → stays on your branch. Test: would
  another organisation need different behaviour here because their rules
  differ?
- **A gap-analysis doc is a contribution even when its code isn't.**
  PR the (suitably anonymised) analysis into `docs/forks/` — the next
  similar organisation's agent learns the domain from it for free.

**Upstream duties (what this repo owes its forks):**

- Migrations stay append-only, never renumbered (Flyway already enforces
  the discipline).
- Rules live in one method (`MembershipStore.insertMembershipPerson` is
  the exemplar — three CRs share it, so a fork changes a rule once).
- Corrections are dated in place, never silently rewritten — a fork's
  agent must be able to trust the docs' history.
- The matrix stays environment-overridable (CR-008) so a fork can point
  it at any stack.

## Hosting: shared operations, not multi-tenant

Fork-per-organisation and single-tenant deployment are the same decision
viewed from two sides. Multi-tenant means one codebase serving N
organisations, which forces the config-option model straight back in.
The natural operations model here is **N forks as N compose projects** —
each with its own database, Keycloak realm, backups, and Caddy ingress
(the deploy assets in `server/deploy/` target exactly this; the compose
`name:` pin exists because two same-named projects on one host was
already a documented bite). An operator can sell *operations* — hosting,
backups, upgrade-replays — without owning the code or the data. The
organisation can always take its fork and leave.

## Honest limits

- **The floor is one technically-confident volunteer** who can run the
  matrix, do a browser walkthrough, and judge an agent's output. That
  widens the audience enormously compared to "organisations with a dev
  team" — but it is not universal.
- **Divergence entropy is real.** Years of fork drift make even
  CR-replay upgrades non-trivial. The charter bounds the cost; it does
  not abolish it.
- **The model depends on agent quality** — specifically on an agent that
  reads the docs, respects the load-bearing list, and treats "matrix
  green" as non-negotiable. The charter exists to be pasted into the
  prompt of a lesser agent.
- Compliance-critical bespoke work (see the donations section of the
  Riverina example) still deserves human professional review; the agent
  drafts the design, it does not sign off on electoral or tax law.

## Worked example

`docs/forks/riverina-state-gap-analysis.md` — a demonstration gap
analysis adapting MemberRoll to a new political party whose crucial need
is party-registration compliance (AEC and NSW/Vic electoral commissions).
It shows the shape: roughly 80% of the system transfers untouched, the
delta is a handful of fork CRs, and the crucial features are exactly the
kind no config surface could ever have anticipated.

## Prior art and positioning (as at July 2026)

The constituent ideas here are each established in current discourse;
this section records where each came from, and which combinations we
could find no precedent for at the time of writing. The novelty claims
are dated claims about the *discourse we could locate*, not about what
someone may have thought first — read them accordingly.

**Established threads this philosophy stands on:**

- **Malleable software** — the closest prior art for the
  anti-configuration stance. Geoffrey Litt's
  ["Malleable software in the age of LLMs"](https://www.geoffreylitt.com/2023/03/25/llm-end-user-programming.html)
  (2023) and the Ink & Switch essay
  ["Malleable Software: Restoring user agency in a world of locked-down apps"](https://www.inkandswitch.com/essay/malleable-software/)
  (Litt, Horowitz, van Hardenberg, 2025) argue that users should reshape
  tools rather than accept vendor configuration, with a lineage running
  through Clay Shirky's "situated software" (2004) and Robin Sloan's
  ["An app can be a home-cooked meal"](https://www.robinsloan.com/notes/home-cooked-app/)
  (2020). Their frame is personal tools and in-app end-user agency;
  notably, the Ink & Switch essay holds that AI coding is
  *not sufficient on its own* for malleability.
- **Spec-driven development** — mainstream by 2025–26
  ([GitHub Spec Kit](https://github.blog/ai-and-ml/generative-ai/spec-driven-development-with-ai-get-started-with-a-new-open-source-toolkit/),
  AWS Kiro, Tessl; see
  [Fowler's comparison](https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html)):
  the spec is the source of truth, code a regenerable output, including
  a named
  ["rebuild test"](https://www.augmentcode.com/guides/spec-as-source-of-truth-rebuildable-codebase)
  (delete the source, regenerate from specs, pass the existing suite) —
  the philosophical cousin of our matrix-as-proof and
  rebase-the-CRs-not-the-diffs. SDD's frame is developing one product,
  not maintaining forks of someone else's.
- **The build-vs-buy inversion** — the "end of SaaS" genre (Klarna
  replacing Salesforce/Workday with in-house AI builds, 2024; Nadella's
  "agents collapse the application layer") makes the economics argument
  in enterprise form: AI makes bespoke cheaper than configured-generic.
  That discourse imagines building from scratch, not forking an
  open-source domain application.
- **Agent-readable repositories** — CLAUDE.md/AGENTS.md conventions are
  standard practice, framed as making *your own* repo workable for
  *your own* agents.

**Where this document appears to break new ground** (as at July 2026,
no precedent located for these specific combinations):

1. **Forkability as the distribution model of an open-source domain
   application** — the repo deliberately packaged (recorded reasoning +
   verification matrix) so that *strangers'* agents can fork it safely;
   the docs-and-matrix discipline treated as the product's
   customisation interface, rather than internal hygiene.
2. **The governing-document gap analysis as the onboarding prompt** —
   "here is our constitution / the relevant Act; find the gaps; write
   the CRs." Likely the most transferable idea here: every association,
   party, co-operative and strata scheme *has* a governing document,
   and none of them is reachable by a config surface.
3. **The fork charter as the answer to "AI coding is not sufficient"**
   — the explicit designed-to-change vs load-bearing distinction,
   PR-back criteria, and gap-analyses-as-contributions (`docs/forks/`)
   supply the missing sufficiency conditions the malleable-software
   literature points at but leaves abstract.
4. **CR-replay as fork-maintenance discipline** — SDD regenerates one
   product from its own specs; replaying an organisation's
   customisation CRs onto a moving upstream base, with the inherited
   matrix as the proof of each replay, is that idea applied to a
   problem SDD doesn't address.
5. **The config-for-closed-legal-enums vs fork-for-structure rule** — a
   crisp decision boundary where the discourse offers only the mood
   that "config is bad now."
6. **A measured working instance.** The genre is manifestos and
   research prototypes; this repo is a production system with a
   4-day/40-commit build record
   (`codebase-size-and-effort-estimate.md`), a ~600-check matrix, and
   the workflow already executed once against a real constitution
   (CR-007/CR-011). At the time of writing, receipts — not the thesis —
   are the scarce contribution.
