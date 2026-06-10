# BACKLOG — NexusPay

Score = (Impact × Confidence) / Effort, each 1–5. Modifiers: +2 correctness/
security defect, +1 unblocks ≥2, +1 closes measured perf gap, −2 irreversible/
high blast radius, −1 elegance-only. Tier per ratchets.risk_map.

claims: (none — single instance)

## Ready (sorted by score)

- **B-006 | Run + record baseline security scans** | security — PARTIAL
  DONE: real local secret-pattern baseline (clean); gitleaks + OSV-Scanner wired
  into CI as gating jobs; AUDITS recorded. REMAINING: semgrep java SAST in CI;
  first CI run's OSV findings triaged (needs push, Q-001) or local tools (Q-003).
  Score (4×5)/2 = **10**.

- **B-011 | Flyway version collisions across modules (V1/V2)** | T3 it-runs
  ledger V1 vs payment/iam/gateway V1 (four V1__), ledger V2 vs iam V2 feed one
  history → "more than one migration with version 1" at startup. Needs a DB to
  confirm. Score (5×4)/3 +2 = **8.7**. AC: renumber into reserved ranges OR
  per-module history tables; verified migrate. Source: audit (ledger/recon).

- **B-014 | Raise coverage on the lowest money/security modules** | test-strength
  IN PROGRESS. Done so far: fraud + payment-orchestration now have money-math
  tests; FX exponent bug fixed (see Done). Still thin: gateway-api 2%, billing
  4%, common 7%, ledger 10%, iam now ~? (rose w/ ApprovalServiceTest). Continue
  on those; ratchet coverage_floor_pct up as it rises. Score (4×4)/3 = **5.3**.

- **B-002 | RLS enforced at runtime** | T3 security (RESEARCH-first)
  `SET LOCAL` runs outside a txn (no-op) AND app connects as table owner
  (bypasses RLS). Real multi-tenant isolation requires a tx-bound GUC +
  non-owner datasource role. Needs a DB to verify. Score (5×4)/4 +2 = **7**.
  AC: research note + design RFC before code.

- **B-003 | Wire fraud + cross-border compliance into the payment path** | T3 security (RFC-first)
  `AssessFraudRiskUseCase` and `CrossBorderComplianceService.validateOrThrow`
  have zero callers — BLOCK/sanctions decisions gate nothing. Score (5×4)/4 +2
  = **7**. AC: RFC (module-boundary impact: gateway→fraud), then gated call.

- **B-012 | CI hardening: pin actions by SHA, add OSV + secret scan** | build-health
  Existing `.github/workflows/ci.yml` + new perpetua-gates. Score (3×4)/2 = **6**.

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

- **B-020 | Integration-test pacing against real ccusage output** | test-strength
  Discovered in B-019: the pure pacing controller is unit-tested, but the
  ccusage JSON parse in perpetua-usage.sh is only verified by graceful-degradation.
  Add a fixture-based test feeding a captured `ccusage blocks --json` sample.
  Score (3×3)/2 = **4.5**.

- **B-021 | Weekly-cap awareness in pacing** | DX
  Discovered in B-019: pacing optimizes the 5h window only. A near-weekly-cap
  signal should force LEAN regardless of the 5h line. ccusage can report it.
  Score (2×3)/2 = **3**.

- **B-022 | Reconcile approved-but-unexecuted refunds (stuck APPROVED)** | T3 money (RFC-first)
  Discovered in B-009 dual review (pre-existing, not introduced): `approve()`
  commits APPROVED in its own tx, then `executeApprovedRefund` runs outside it;
  if the gateway throws, the approval is APPROVED-forever and a retry's approve()
  throws "not pending" → the legitimate refund never executes, needs manual
  recovery. The deterministic idempotency key (B-009) now makes a re-drive safe.
  Fix: an outbox/reconciler over APPROVED-unexecuted refunds keyed on the same
  idempotency key (fold into B-002/outbox work). Score (4×4)/4 +2 = **6**.

## Done
- **B-007** (2026-06-10, Q-007 approved) DELETED the dead routing A/B framework
  (controller + service + test; ~250 LOC) — subtraction §7; re-add when routing is
  live (B-003). ADR-008. Inert abTest fields left in place.
- **B-018** (2026-06-10) OutboxRelay leader-lock release is now an atomic
  owner-checked Lua compare-and-delete (was non-atomic GET-then-DELETE) — mirrors
  the reviewed SchedulerLock pattern; closes the release-race the B-001 review found.
- **B-014b** (2026-06-10) regression tests locking L-001 (per-currency journal
  balance: cross-currency raw-sum-zero rejected; multi-ccy per-currency-balanced
  accepted) and L-003 (canonical account-id helpers + idempotent 7-account
  provisioning + tenant propagation). +6 tests.
- **B-014a** (2026-06-10) FX currency-exponent convert bug (still-open HIGH from
  the audit) — `FxRate.convert`/`FxRateLock.convert`/`CurrencyConversion.fxGainLoss`
  all multiplied raw minor units by a major-unit rate (USD→JPY 100× off). Extracted
  exponent-aware `CurrencyMath`, fixed all 3 sites; CurrencyMathTest+FrmAmountsTest
  (11 tests) — first tests for the fraud + payment-orchestration modules. Review SHIP
  (found the 3rd site). L-019.
- **B-005** (2026-06-10) JaCoCo wired on all modules (per-module XML+HTML);
  measured aggregate line coverage 24%; ratchet `coverage_floor_pct=23` enforced
  in CI (perpetua-gates). Exposed the thin modules for B-014.
- **B-013** (2026-06-10) build-environment docs in CONTRIBUTING (JDK-21/JAVA_HOME,
  gradlew.bat, temp-dir loopback gotcha, Docker-skip integration tests, coverage cmd).
- **B-009** (2026-06-10) maker-checker refund execute-once + idempotency —
  atomic conditional approve (`transitionFromPending`, rows-affected==1) closes
  the concurrent double-approve race; tenant-ownership check closes cross-tenant
  approve; deterministic idempotency key (`refund-approval-<id>`) dedups any
  duplicate execution at the gateway. 9 tests. Reviews: security SHIP, adversarial
  flagged a pre-existing stuck-approval recovery gap → tracked as B-022 (not a
  regression; commit boundary unchanged). Threat model: duplicate-refund race
  OPEN→CLOSED.
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
