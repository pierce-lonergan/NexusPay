# ROADMAP — NexusPay

## NOW (this + next few sessions)
- Close the cheap, high-confidence correctness defects: settlement-ingest jsonb
  (B-010), reconciliation PARTIAL handling (B-008).
- Kill multi-instance double-billing: scheduler distributed locks (B-001).
- Security hygiene: secrets fail-fast (B-004); run + record baseline scans (B-006).
- Stand up measurement: coverage (B-005) so ratchets become real.

## NEXT (weeks)
- Tests for the zero-test money modules: analytics, fraud, payment-orchestration
  (B-014) — start with FX/FRM/rollup math.
- Maker-checker refund execute-once + idempotency (B-009).
- Flyway version-collision fix (B-011) — needs a DB to verify (Q-004).
- CI hardening: pin actions, OSV + secret scan in CI (B-012); branch protection.

## LATER (milestones)
- **Multi-tenancy actually enforced** (B-002): tx-bound RLS GUC + non-owner
  datasource role. Milestone: integration test proving tenant A cannot read B.
- **Protective services become gates** (B-003): fraud BLOCK + sanctions screening
  enforced in the payment path, not advisory. Milestone: integration test that a
  fraud-BLOCK / sanctioned-country payment is rejected.
- **Performance baseline**: pick the payment create→authorize hot path, build a
  JMH/macro benchmark, set ratchets baselines, then optimize evidence-first.
- **Test-strength**: PIT mutation wired; mutation_floor ≥ 0.70 on ledger/billing/
  vault core.

## MILESTONES
- M1: app boots against the dev stack (Postgres/Kafka/Valkey) and migrates clean.
- M2: zero open HIGH security findings; all suppressions expire.
- M3: ledger + billing + vault at mutation ≥ 0.70; coverage ratchet > 0.
