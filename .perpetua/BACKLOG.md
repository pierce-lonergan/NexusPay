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

- **B-014 | Raise coverage on the lowest money/security modules** | test-strength
  IN PROGRESS. Done so far: fraud + payment-orchestration now have money-math
  tests; FX exponent bug fixed (see Done). Still thin: gateway-api 2%, billing
  4%, common 7%, ledger 10%, iam now ~? (rose w/ ApprovalServiceTest). Continue
  on those; ratchet coverage_floor_pct up as it rises. Score (4×4)/3 = **5.3**.

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
- **B-002 | RLS enforced at runtime** (machinery 2026-06-13) — the two-part bug (pre-tx SET LOCAL
  no-op + owner bypasses RLS) is fixed: non-owner nexuspay_app role + in-tx set_config GUC, all
  dormant behind rls.enforce=false and proven by RlsDormancyIT/RlsEnforceIT. RUNTIME enforcement
  (the actual flip) remains: B-002-activation-tenant (pre-flip) → B-002-cutover (human-gated). See
  ADR-010/012, the "B-002 RLS runtime activation" section below.
- **B-003 | Fraud + cross-border compliance in the payment path** — DONE via B-024 (2026-06-10):
  @Primary GatedPaymentGateway at the PaymentGatewayPort screens all callers; sanctions hard-block,
  fraud BLOCK→REVIEW flow-aware. ADR-009/011. Residual hardening: B-025/B-026/B-028/B-029/B-030.
- **B-011 | Flyway version collisions across modules** — DONE 2026-06-10 (e6c2392). Was the single
  blocker hiding the never-run integration suite; fixed collisions + leaf-locations + fail-on-missing,
  then the schema↔entity drift cascade it exposed. CI now boots the full app on real infra. L-023–L-030.
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
- ~~**B-002-activation | tx-manager + system routing + job routing + FORCE (C5-C7)**~~ | DONE 2026-06-13 (dormant, CI-green; commits 719ee55 + f06d483). The machinery is LANDED behind `rls.enforce=false`: single EMF over a `RoleRoutingDataSource` (app/system Hikari pools) + `RlsRoutingTransactionManager.doBegin` set_config GUC; `@SystemTransactional` (relocated to `io.nexuspay.common.rls`) + `SystemRoleAspect` thread-local role pin; the 16 genuinely cross-tenant @Scheduled jobs annotated SYSTEM (15 + TrialExpirationScheduler added in the post-ship review); broken `TenantAwareDataSourceConfig` deleted; `R__rls_force_owner.sql` repeatable FORCE (gated by `${rlsforce}`, default false); `analytics/V3022` normalizes the 7 analytics policies to the fail-closed `current_tenant_id()` helper (review #3). Proven by RlsDormancyIT (everything absent + no table forced at enforce=false) + RlsEnforceIT (boots on nexuspay_app under the `rls-enforce` profile: tenant isolation, fail-closed-unbound, @SystemTransactional bypass, every RLS table FORCE'd, analytics policies fail-closed). Post-ship adversarial review = SHIP-WITH-MINORS (0 blocker/0 must-fix; 8 minors fixed/documented). See ADR-012 (+addendum), L-033/34/35.
- ~~**B-002-activation-tenant | bind TenantContext on the 6 consumers + per-item billing writes**~~ | DONE 2026-06-14 (dormant, CI-green via PR #2; commits 79937ea→6cfebc3). Shared `TenantWorkRunner` (interface in `common`; `AppTenantTransactionTemplate` enforce=true / `InlineTenantWorkRunner` dormant, in `app`): `runInTenant`/`callInTenant` open a REQUIRES_NEW APP+tenant tx; `bindTenant` binds APP+tenant WITHOUT an enclosing tx (so an inner @Transactional keeps its own isolation — L-036). The 6 consumers bind before their tx (ledger uses bindTenant to preserve its SERIALIZABLE journal write; analytics/billing drop @Transactional + route through `runInTenant(metadata.tenant_id?:"default")`; gateway loads endpoints tenant-scoped, gated on the enforce flag — L-037 — HTTP loop outside the tx; fraud = comment). The 3 billing sweeps run discovery under the SYSTEM pin then each per-item write via the helper on APP+tenant. Proven by the helper enforce-IT (APP+tenant write/read/WITH-CHECK/SYSTEM-nesting) + an analytics-consumer enforce-IT (bind→WITH-CHECK write) + the dormancy IT (InlineTenantWorkRunner active, AppTenantTransactionTemplate absent). Adversarial review = SHIP-WITH-FIXES → both fixes applied. See ADR-013, L-036/037/038. REMAINING (deferred to B-002-cutover, documented): (a) the per-event tenant on the `nexuspay.payments` topic — Step 0 below; (b) deferred ITs — gateway (RestClient/HTTP coupling), the 3 sweeps (heavy billing fixtures), routing/ledger consumers (redundant with the analytics+helper ITs).
- **B-002-cutover Step 0 | stamp the real tenant on `nexuspay.payments`** | T3 | MANDATORY PRE-FLIP. `HyperSwitchWebhookController` publishes outbox events with NO tenant (metadata={source,original_event_id}; the 4-arg OutboxEvent ctor hardcodes tenant_id="default"), so all 4 payments-topic consumers bind "default" under enforcement → real-tenant rows are invisible (silent no-op / fail-closed). Resolve the merchant→tenant mapping at HS ingest, stamp `metadata.tenant_id` + use a tenant-aware OutboxEvent ctor; optionally add a `tenant_id` Kafka header in OutboxRelay (enables a future thin RecordInterceptor). Do NOT flip prod `enforce=true` until this lands. Then write the deferred consumer/sweep ITs against real-tenant fixtures.
- **B-002-cutover | the human-gated flip itself** | T3 security | Provision the real `nexuspay_system` + `nexuspay_app` secrets (out-of-band ALTER ROLE), then in staging→canary→fleet: set the app role to `nexuspay_app`, `rls.enforce=true`, and (paired) `spring.flyway.placeholders.rlsforce=true` + bump `R__rls_force_owner.sql` so Flyway re-applies FORCE. Pre-req: B-002-activation-tenant MUST be green first. Rollback: enforce=false (app reverts to owner) and/or rlsforce=false + file bump (NO FORCE). The RlsEnforceIT is the pre-flip gate. Resolves review M2 (capture-hold GUC visibility) once consumers bind tenant.
