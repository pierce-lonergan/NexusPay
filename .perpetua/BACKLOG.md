# BACKLOG — NexusPay

Score = (Impact × Confidence) / Effort, each 1–5. Modifiers: +2 correctness/
security defect, +1 unblocks ≥2, +1 closes measured perf gap, −2 irreversible/
high blast radius, −1 elegance-only. Tier per ratchets.risk_map.

claims: (none — single instance)

## Ready (sorted by score)

- **B-010 | Reconciliation settlement ingest INSERT fails (jsonb mapping)** | T2 correctness
  `SettlementRecordEntity.rawData` is a String on a `jsonb` column with no
  `@JdbcTypeCode(SqlTypes.JSON)` (cf. working `JournalEntryEntity`); every
  ingest INSERT aborts. `StripeCsvParser` also stores a raw CSV line (not JSON).
  Score (4×4)/1 +2 = **18**. AC: entity annotated, parser stores valid JSON,
  mapping test. Source: audit (ledger/recon).

- **B-001 | Distributed locks on billing schedulers (double-billing)** | T3 money
  `RenewalScheduler`, `TrialExpirationScheduler`, `DunningService` run on every
  instance with no leader lock → N instances charge the same invoice N times.
  Pattern exists (`OutboxRelay` Valkey lock). Score (5×4)/2 +2 = **12**.
  AC: shared lock helper, 3 schedulers guarded, unit tests for lock skip/run.
  Source: audit (billing).

- **B-004 | Secrets default to committed dev values, no prod guard** | T2 security
  `nexuspay.session.jwt-secret`, vault master-key, webhook-secret resolve to
  in-source defaults; a missing env var in prod signs/encrypts with a public
  key. Score (4×5)/2 +2 = **12**. AC: fail-fast at startup when a known default
  is resolved under a non-local profile; unit test. Source: audit (iam/app).

- **B-006 | Run + record baseline security scans** | security
  gitleaks (history once), OSV/dependency-check, semgrep java. Findings →
  security/AUDITS.md; Critical/High → top of backlog. Score (4×5)/2 = **10**.
  AC: scans run, AUDITS.md populated, ratchets high_vulns reflects reality.

- **B-009 | Maker-checker refund: execute-once + idempotency key** | T3 money/security
  Approved refund executes `createRefund(idempotencyKey=null)` in a separate
  tx with no execute-once guard → duplicate/lost refund on retry. Score
  (4×4)/3 +2 = **7.3**. AC: deterministic idempotency key from approval id;
  APPROVED→EXECUTED guard; tests. Source: audit (gateway/iam).

- **B-008 | Reconciliation PARTIAL results silently swallowed** | T2 correctness
  "payment matched, ledger entry missing" → `markUnmatched` but excluded from
  exception creation and run counters (total ≠ matched+unmatched+exceptions).
  Score (3×4)/2 +2 = **8**. AC: PARTIAL → MISSING_LEDGER_ENTRY exception +
  counted; unit test. Source: audit (recon).

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

## Done (this session)
- (bootstrap fixes committed as 4a1c6ea — see DIGEST/LESSONS)
