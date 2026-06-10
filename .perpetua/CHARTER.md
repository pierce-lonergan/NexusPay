# PERPETUA Charter — NexusPay            (HUMAN-OWNED — agent may not edit)

> STATUS: DRAFT — awaiting human ratification (see QUESTIONS.md Q-001).
> Until ratified, the agent operates at L1 (branch + local commits, no push,
> no PR, no merge to main) with conservative defaults.

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
- tests: unit test count never decreases (baseline 201); 0 failing on main
- security: zero High+ findings open; all suppressions carry an expiry
- performance: payment create→authorize p95 bench target TBD after first profile
- it-runs: `bootRun` reaches "Started NexusPayApplication" against the dev stack

## Non-goals / Forbidden
- No new runtime dependency without a BLOCKING question + supply-chain check.
- Never weaken a test, loosen an assertion, or add a coverage/scan exclusion to
  go green. Never commit a real secret. Never push or open PRs until ratified.
- Do not touch `docs/gaps/known-gaps.md` history (it is the team's record).

## Autonomy
level: L1
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
