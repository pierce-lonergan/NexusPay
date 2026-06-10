# BACKLOG — NexusPay

Score = (Impact × Confidence) / Effort, each 1–5. Modifiers: +2 correctness/
security defect, +1 unblocks ≥2, +1 closes measured perf gap, −2 irreversible/
high blast radius, −1 elegance-only. Tier per ratchets.risk_map.

claims: (none — single instance)

## Ready (sorted by score)

- **B-006 | Run + record baseline security scans** | security
  gitleaks (history once), OSV/dependency-check, semgrep java. Findings →
  security/AUDITS.md; Critical/High → top of backlog. Score (4×5)/2 = **10**.
  AC: scans run, AUDITS.md populated, ratchets high_vulns reflects reality.

- **B-009 | Maker-checker refund: execute-once + idempotency key** | T3 money/security
  Approved refund executes `createRefund(idempotencyKey=null)` in a separate
  tx with no execute-once guard → duplicate/lost refund on retry. Score
  (4×4)/3 +2 = **7.3**. AC: deterministic idempotency key from approval id;
  APPROVED→EXECUTED guard; tests. Source: audit (gateway/iam).

- **B-005 | Wire coverage (JaCoCo) + set real ratchet baseline** | test-strength
  No coverage measured. Add jacoco, run, set `coverage_floor` to reality, gate
  in CI. Score (3×5)/2 = **7.5**. AC: jacoco report, ratchets updated, CI check.

- **B-011 | Flyway version collisions across modules (V1/V2)** | T3 it-runs
  ledger V1 vs payment/iam/gateway V1 (four V1__), ledger V2 vs iam V2 feed one
  history → "more than one migration with version 1" at startup. Needs a DB to
  confirm. Score (5×4)/3 +2 = **8.7**. AC: renumber into reserved ranges OR
  per-module history tables; verified migrate. Source: audit (ledger/recon).

- **B-014 | Tests for the zero-test money modules** | test-strength
  analytics, fraud, payment-orchestration have NO unit tests. Target the money/
  security paths first (FX convert, FRM amount scaling, rollup math). Score
  (4×4)/3 = **5.3** (×rotation priority). AC: ≥1 test class per money path.

- **B-002 | RLS enforced at runtime** | T3 security (RESEARCH-first)
  `SET LOCAL` runs outside a txn (no-op) AND app connects as table owner
  (bypasses RLS). Real multi-tenant isolation requires a tx-bound GUC +
  non-owner datasource role. Needs a DB to verify. Score (5×4)/4 +2 = **7**.
  AC: research note + design RFC before code.

- **B-003 | Wire fraud + cross-border compliance into the payment path** | T3 security (RFC-first)
  `AssessFraudRiskUseCase` and `CrossBorderComplianceService.validateOrThrow`
  have zero callers — BLOCK/sanctions decisions gate nothing. Score (5×4)/4 +2
  = **7**. AC: RFC (module-boundary impact: gateway→fraud), then gated call.

- **B-007 | Routing A/B framework is dead code — wire or remove** | T2 subtraction
  `RoutingEngine` never tags decisions / records outcomes, so significance is
  always false. Decide: wire it or delete it (subtraction mandate). Score
  (2×4)/2 = **4**. AC: ADR + either path.

- **B-012 | CI hardening: pin actions by SHA, add OSV + secret scan** | build-health
  Existing `.github/workflows/ci.yml` + new perpetua-gates. Score (3×4)/2 = **6**.

- **B-013 | Document the build environment** | docs
  README/CONTRIBUTING: JDK 21 requirement, gradlew.bat, temp-dir/loopback quirk.
  Score (3×5)/2 = **7.5**.

- **B-015 | StripeCsvParser uses naive split(",") — RFC-4180 violation** | T2 correctness
  Discovered during B-010. A quoted `description` containing a comma shifts every
  later column; the row then fails numeric parse and is silently dropped (money
  exits reconciliation with no exception record). Score (3×4)/2 +2 = **8**. AC:
  quote-aware CSV parse; dropped rows become persisted exceptions, not warnings.

- **B-016 | Testcontainers jsonb round-trip test for settlement ingest** | test-strength
  Discovered during B-010 review. The jsonb INSERT (the bug's actual failure
  point) is only unit-tested at the parser level. Add a @DataJpaTest/Testcontainers
  test asserting `jsonb_typeof(raw_data)='object'` after save. Needs Docker (Q-004).
  Score (3×4)/2 = **6**.

- **B-017 | Regression test: fail-closed lock relies on due-based re-selection** | test-strength
  Discovered in B-001 review. The scheduler lock's fail-closed safety assumes a
  skipped cycle re-selects the same subscriptions next run. If renewal selection
  ever became consume-once, fail-closed would silently drop charges. Pin it:
  assert findDueForRenewal re-returns un-renewed subs after a skipped cycle.
  Score (3×4)/2 = **6**.

- **B-018 | Apply atomic-release + lease-renewal to OutboxRelay leader lock** | T2
  Discovered in B-001 review: `OutboxRelay.shutdown()` has the same non-atomic
  GET-then-DELETE release the billing lock just fixed (fail-open is OK there, but
  the release race isn't). Reuse the SchedulerLock pattern. Score (2×4)/2 = **4**.

- **B-020 | Integration-test pacing against real ccusage output** | test-strength
  Discovered in B-019: the pure pacing controller is unit-tested, but the
  ccusage JSON parse in perpetua-usage.sh is only verified by graceful-degradation.
  Add a fixture-based test feeding a captured `ccusage blocks --json` sample.
  Score (3×3)/2 = **4.5**.

- **B-021 | Weekly-cap awareness in pacing** | DX
  Discovered in B-019: pacing optimizes the 5h window only. A near-weekly-cap
  signal should force LEAN regardless of the 5h line. ccusage can report it.
  Score (2×3)/2 = **3**.

## Done
- **B-008** (2026-06-10) reconciliation PARTIAL → MISSING_LEDGER_ENTRY exception,
  counted (run buckets now partition: total=matched+unmatched+exceptions). 4 tests.
  Adversarial review: SHIP.
- **B-004** (2026-06-10) secrets fail-fast — `StartupSecretsValidator` warns in
  dev, THROWS under a prod-like profile (or NEXUSPAY_REQUIRE_MANAGED_SECRETS=true)
  when a built-in default secret is in effect; added `application-production.yml`
  (forces the guard) + a drift-guard test so the control can't fail open. 8 tests.
  Adversarial review: SHIP (both SHOULDs addressed).
- **B-019** (2026-06-10, human-directed) token-aware adaptive scheduling —
  `perpetua-pace.sh` (pure controller, 11 unit tests) + `perpetua-usage.sh`
  (ccusage wrapper, graceful degradation) + harness v3 pacing + CLAUDE.md rigor
  dial. Fills the 5h window with productive Fable-5 work; server caps remain the
  backstop. ADR-007. Follow-ups B-020, B-021.
- **B-001** (2026-06-10) billing scheduler distributed locks — `SchedulerLock`
  (fail-closed, self-renewing lease, atomic owner-checked Lua release, reentrancy
  guard) wraps all 3 billing crons; 10 tests. Adversarial review found a TTL-
  expiry-mid-run double-charge BLOCKER → fixed with lease renewal; security
  review SHIP. ADR-006. Threat-model: B-001 OPEN→PARTIALLY CLOSED.
- **B-010** (2026-06-09→10) settlement-ingest jsonb mapping — entity annotated
  `@JdbcTypeCode(SqlTypes.JSON)`, StripeCsvParser emits valid JSON; 4-test
  `StripeCsvParserTest` added (test count 201→205). Adversarial review: SHIP.
- (bootstrap fixes committed as 4a1c6ea — see DIGEST/LESSONS)
