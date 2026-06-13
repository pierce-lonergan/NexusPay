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

- ~~**B-011 | Flyway version collisions across modules (V1/V2)**~~ | DONE 2026-06-10 (e6c2392). Far bigger than a version bump: it was the single blocker hiding the entire never-run integration suite. Fixed collisions + leaf-locations + fail-on-missing, then the whole schema↔entity drift + wiring cascade it exposed. CI now boots the full app on real infra and all 13 ITs pass (263 green). See DIGEST + L-023–L-030. Residual: B-002 RLS effectiveness still open.
  RFC READY → research/rfc-b011-flyway-collisions.md (confirmed: 1 Flyway/1 history,
  4×V1/2×V2 collide + suspected base-recursion×explicit-location double-scan). Fix:
  de-dup locations + renumber to unique band + FlywayMigrationIT. APPLY+VERIFY needs
  Postgres (Q-004/CI). Score (5×4)/3 +2 = **8.7**.

- **B-014 | Raise coverage on the lowest money/security modules** | test-strength
  IN PROGRESS. Done so far: fraud + payment-orchestration now have money-math
  tests; FX exponent bug fixed (see Done). Still thin: gateway-api 2%, billing
  4%, common 7%, ledger 10%, iam now ~? (rose w/ ApprovalServiceTest). Continue
  on those; ratchet coverage_floor_pct up as it rises. Score (4×4)/3 = **5.3**.

- **B-002 | RLS enforced at runtime** | T3 security
  RFC READY → research/rfc-b002-rls-runtime.md (confirmed two-part bug: SET LOCAL
  pre-tx no-op + owner role bypasses RLS). Fix: set_config(...,true) in-tx +
  non-owner nexuspay_app role + RlsIsolationIT. APPLY+VERIFY needs Postgres
  (Q-004/CI) — wrong RLS leaks or blocks all rows, so must not ship blind.
  Score (5×4)/4 +2 = **7**.

- **B-003 | Wire fraud + cross-border compliance into the payment path** | T3 security
  RFC READY → research/rfc-b003-gate-fraud-sanctions.md (synchronous pre-auth gate
  before PaymentGatewayPort; BLOCK→reject, REVIEW→hold capture, sanctioned→403;
  modulith boundary: add fraud to gateway OR gate in payment-orchestration).
  Implementable here (no DB) — own branch, dual T3 review. Score (5×4)/4 +2 = **7**.

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

## Triaged from the first real CI scans (2026-06-10, B-006 now operational)
- **B-023 | checkout-sdk npm dev-dependency vulns** | T2 | OSV's first CI run (report-only, §15.3) flagged 7 vulns (1 Critical, 1 High, 5 Medium) — ALL in `checkout-sdk/package-lock.json` (the frontend SDK), ALL **dev** dependencies, NONE in the Java backend runtime: vitest 1.6.1→3.2.6 (9.8 Crit), picomatch→4.0.4 (7.5 High), vite→6.4.2, postcss→8.5.10, esbuild→0.25.0, ws→8.20.1. All have fix versions. Fix = bump checkout-sdk devDeps + re-test the SDK build, then flip the OSV gate from report-only to blocking (ratchets high_vulns_max=0). Logged in security/AUDITS.md.

## Triaged from the B-003 T3 dual review (2026-06-10) — pre-auth gate is a first cut
The fraud half is sound + tested; these are the gaps the adversarial + security
reviews found. The gate must NOT be relied on as a complete OFAC sanctions control
until B-025/B-026 land. See ADR-009.
- ~~**B-024 | Gate coverage**~~ | DONE 2026-06-10 (C1+C2, CI-green, T3-reviewed). @Primary GatedPaymentGateway at the PaymentGatewayPort boundary covers all callers (gateway/billing/workflow inherit it); confirm always screens + rejects flagged auto-capture; flow-aware (sanctions hard-block all; fraud BLOCK→REVIEW downgrade on server rails, capture+flag not hold). ADR-011.
- ~~**B-027 | REVIEW capture-hold enforcement + assessment linkage**~~ | DONE (C2). payment_capture_hold table (RLS) enforces the hold at capture + links payment→assessment. Residual: idempotent-fraud-on-retry (the velocity double-count part) folded into B-029.
- **B-025 | Sanctions geography must be server-authoritative, not client metadata** | T3 security | HIGH. `GateSignals.fromRequest` reads source/destination country from client-supplied request metadata; a caller omits/forges them to evade the OFAC screen. Derive destination from merchant/tenant config, source from trusted-edge geo-IP and/or BIN→issuer-country; treat client metadata as advisory; treat "country unknown on a cross-border-capable flow" as REVIEW/EDD, not ALLOW.
- **B-026 | OFAC list: fix parser + fail-closed + health surfacing** | T3 security | HIGH. `SanctionsListAdapter.fetchOfacSanctionedCountries` expects ISO-2 codes but the CSL CSV stores country NAMES → parses to empty → silently retains the 4-country static fallback (KP/IR/SY/CU) forever (`isOfacAvailable()` never flips true). Fix = map names→ISO-2 (or use a coded feed) + parser test vs a real CSL sample; treat empty/stale/failed refresh as a readiness-critical alarm; make the compliance check fail CLOSED (block/5xx) when screening is unavailable rather than returning ALLOWED.
- **B-027 | REVIEW capture-hold enforcement + idempotent fraud + assessment linkage** | T3 | MEDIUM. (a) REVIEW only flips `capture_method=manual` on create; persist (paymentId→assessmentId, PENDING_REVIEW) and refuse capture/confirm while PENDING_REVIEW. (b) Retried Idempotency-Key re-runs `assess()` → double-counts velocity, duplicate assessment rows + events; dedup on (tenant, idempotency key). (c) write the real gateway payment id back onto the assessment (currently keyed by the preauth ref).
- **B-028 | Fraud fail-open posture + adjacent hardening** | T2 | LOW/MEDIUM. Document + alert the fraud fail-open posture (FRM-down + sparse context → ALLOW; consider REVIEW-not-ALLOW on higher amounts when FRM unavailable). Stop echoing the matched country in the `cross_border_blocked` 403 body (keep in logs). `FraudAssessmentController` review endpoints trust a spoofable `X-Tenant-Id` header. PAN-in-domain-object: `PaymentRequest.paymentMethodData` carries a raw PAN — add a masking `toString` + logging filter (PCI). Normalize geo-rule config codes (`trim().toUpperCase()`).

## Triaged from the B-024 T3 review (2026-06-10) — port-boundary gate
- **B-029 | Gate mode + tenant must be server-authoritative at the port boundary** | T3 security | HIGH (latent). `ScreeningMode.fromMetadata` (the SERVER_RECURRING/OTHER downgrade) and `GatedPaymentGateway.tenantFrom` read `source`/`workflow`/`tenant_id` from request metadata. Current callers stamp these server-side (REST strips+stamps from the principal; billing/workflow from constants), so NOT exploitable today — but a future caller forwarding client metadata would let a client claim the softer rail or fragment fraud-velocity across fabricated tenants. Fix: derive mode + tenant from a trusted call-site identity at every ingress (ties into B-002's per-job tenant binding), not from request metadata inside the gate. Also fold in B-027's idempotent-fraud (dedup assess() on (tenant, idempotency key) so retries don't double-count velocity).
- **B-030 | Server-rail fraud-flag review event/queue + capture-hold lifecycle** | T2 | MEDIUM. A server-rail (billing/workflow) fraud REVIEW now captures + logs (ADR-011 M1 fix) but emits no durable analyst signal — add a `payment.review_required` event + an analyst queue/release path. Also: voidPayment leaves an orphaned HELD capture-hold row (transition to terminal on void); `release()` is a silent no-op on missing id; correlate the create-vs-confirm assessment refs. (Review LOW items L2/L3/L4/N5.)

## B-002 RLS runtime activation (remaining — staged, co-lands with the human cutover)
- **B-002-activation | tx-manager + system EMF + 16-job routing + FORCE** | T3 security | The dormant foundation is LANDED (proving IT, system role/grants/MV ownership, WITH-CHECK write-leak fix — all CI-green). Remaining (rfc-b002 C5-C7, must land together with the cutover): `RlsAwareJpaTransactionManager.doBegin` set_config GUC; a second `systemDataSource`/EMF/`systemTxManager` on `nexuspay_system`; re-route the ~16 cross-tenant @Scheduled jobs (system tx-manager / per-tenant `TenantWork.runAs` for billing) — incl. routing analytics rollups to the BYPASSRLS role (review L5); delete the broken `TenantAwareDataSource` decorator; migration set 2 `FORCE ROW LEVEL SECURITY` + a pre-written `NO FORCE` revert. HUMAN-GATED activation: provision the `nexuspay_system` secret, flip `NEXUSPAY_DB_USER→nexuspay_app` + `rls.enforce=true` + `force-enabled=true` in staging→canary→fleet. The new IT suite is the gate. Also resolves review M2 (set the GUC on capture-hold reads/writes so a hold is tenant-visible at capture). Deferred this session: high blast radius (2nd EMF context-boot + 16 jobs) + cannot validate the cutover without a staging env — not safe to rush at session tail.
