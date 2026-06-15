# PERPETUA Charter — NexusPay            (HUMAN-OWNED — agent edits ONLY on a recorded, explicit per-edit human instruction; no standing edit authority)

> STATUS: RATIFIED — operating at L2 (human-ratified Q-001 2026-06-10; re-authorized
> in-session 2026-06-14).
> L2 operating rule: the agent pushes freely on `perpetua/**` branches and merges to
> main when ALL CI gates pass, EXCEPT tier-3 changes (auth/authz, crypto, parsing/
> deserialization, SQL/shell/path, network boundary, concurrency, money/data-integrity,
> migrations — the `ratchets.json` risk_map.t3_globs) which ALWAYS go via PR review
> even at L2 (§3 / §17.3). External/L3 actions remain out of scope
> (whitelisted_external_actions: []).
> STILL HUMAN-GATED: the production RLS cutover (B-002-cutover — the irreversible
> `rls.enforce=true` flip), any L3 / external side effect, and branch protection on
> main (require the CI workflow, forbid force-push) — the §18.3 structural backstop,
> still PENDING the human (Q-002 unanswered).
> PROVENANCE: this file is HUMAN-OWNED. The human ratified L2 in Q-001 (2026-06-10)
> and explicitly re-authorized this sync in-session (2026-06-14); the agent applied
> this edit ONLY under that explicit human instruction. The agent is NOT self-elevating.
> See DECISIONS.md ADR-016.

## Mission
NexusPay is an enterprise payment-operations platform layered on HyperSwitch:
double-entry ledger, fraud prevention, subscription billing, dispute
management, FX/cross-border, vault (PCI card storage), marketplace splits, B2B,
and production observability — a Spring Modulith monolith (Java 21, Spring Boot
3.2, PostgreSQL, Kafka, Valkey). "Better" means: money is never lost or
double-moved, tenants are isolated, the app actually boots and runs, and every
change is verified — correctness and security first, then performance and DX.

## Goals (ranked)
1. **Financial integrity** — no lost/double/mis-attributed money; double-entry
   invariants hold per currency; idempotency is real, not assumed.
2. **Security & tenant isolation** — RLS enforced at runtime; secrets never
   defaulted in prod; PCI data protected; every diff passes the security lens.
3. **It runs** — the app boots and migrates cleanly; startup blockers are bugs.
4. **Verified quality** — test strength (mutation/property/fuzz) ratchets up;
   the modules with zero tests (analytics, fraud, payment-orchestration) get them.
5. **Performance & DX** — evidence-first; profile before optimizing.

## North-star targets (measurable; define "good enough")
- correctness: mutation score ≥ 0.70 on ledger + billing + vault core (once wired)
- tests: unit test count never decreases (floor tracked in `ratchets.json` →
  `test_count_floor`, currently 680 — that file is the source of truth, §18.1);
  0 failing on main
- security: zero High+ findings open; all suppressions carry an expiry
- performance: payment create→authorize p95 bench target TBD after first profile
- it-runs: `bootRun` reaches "Started NexusPayApplication" against the dev stack

## Non-goals / Forbidden
- No new runtime dependency without a BLOCKING question + supply-chain check.
- Never weaken a test, loosen an assertion, or add a coverage/scan exclusion to
  go green. Never commit a real secret. Push freely on `perpetua/**` branches;
  merge to main only when ALL CI gates pass; tier-3 changes ALWAYS go via PR
  review (§17.3) — never merge a T3 change directly even at L2.
- Do not touch `docs/gaps/known-gaps.md` history (it is the team's record).
- Always human-gated (the agent must NOT do these alone): the production RLS
  cutover (B-002-cutover — the `rls.enforce=true` flip and its mandatory Step 0);
  any L3 / external side effect (deploys, releases, enabling branch protection,
  anything outside the repo root not CHARTER-whitelisted); branch protection on
  main is still PENDING the human (Q-002).

## Autonomy
level: L2
whitelisted_external_actions: []

## Config
iterations_per_session: 3
wall_clock_minutes: 90
meta_review_every_n_sessions: 10
divergence_every_n_sessions: 5
deep_audit_every_n_sessions: 7
explore_exploit: "20/80"
models: { orchestrator: fable-5, implementer: sonnet, chores: haiku }
parallel_instances: 1

## Definition of value (tie-breaker prose)
This is a payments system: a silent money bug or a cross-tenant leak is worth
more to fix than any feature. Prefer small, verified, reversible increments.
When the app can't boot or a tenant can see another tenant's data, nothing else
matters until it's fixed.
