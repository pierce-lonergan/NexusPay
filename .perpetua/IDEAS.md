# IDEAS — innovation funnel (raw → triaged → spike → RFC → backlog)

## Raw
- Context-load smoke test: a CI test that starts the Spring context with mocked
  infra (no Docker) to catch unsatisfied-bean / cycle / scan-root regressions
  (would have caught L-011/12/13). → strong candidate, promote to BACKLOG.
- Money type everywhere: ledger/reconciliation pass raw `long` + `String currency`
  instead of the shared `Money` — the root enabler of the cross-currency bug.
  Adopt `Money` in ledger domain. (subtraction-adjacent: deletes ad-hoc pairs.)
- DB CHECK constraint enforcing per-currency debit=credit on `postings`
  (defense-in-depth for L-001).
- Property tests: Money is a textbook target (associativity, identity, inverse,
  currency-mismatch) — jqwik.
- Differential test harness for any future ledger-balance refactor.
- Replace mutable in-memory singletons (DCC offers, A/B counters, circuit-breaker
  state) with Valkey-backed state so multi-instance behaves.
- Outbox: at-least-once is only as good as the consumer dedup — add an
  idempotency/inbox table keyed by event_id on money-moving consumers.
- Subtraction: delete the dead routing A/B framework if not wired (B-007).
- DX: a `make verify` / Gradle task bundling build+test+scan so every session
  (and contributor) runs the same gates locally.

## Triaged / Spike / RFC
- (none yet)
