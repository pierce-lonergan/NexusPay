# BACKLOG — NexusPay

Score = (Impact × Confidence) / Effort, each 1–5. Modifiers: +2 correctness/
security defect, +1 unblocks ≥2, +1 closes measured perf gap, −2 irreversible/
high blast radius, −1 elegance-only. Tier per ratchets.risk_map.

claims: (none — single instance)

## ⚠ SECURITY — code-sweep 2026-06-15 (TOP PRIORITY; full detail in security/AUDITS.md)
48 confirmed defects (13 CRIT / 17 HIGH / 12 MED / 6 LOW) → 22 remediations. Root cause: client-`X-Tenant-Id`
trust + global-PK reads + no authz aspect + RLS dormant ⇒ tenant isolation effectively OFF outside gateway-api.
App-level fixes make it safe regardless of RLS; RLS cutover (B-002-cutover) is now URGENT but human-gated.
Fix program (each batch = its own T3 PR via the fusion topology; diverse implementers → CI filter → select-and-repair → fresh review):
- ~~**SEC-BATCH-1 (CRIT) tenant-authority hardening**~~ — DONE 2026-06-15 (T3 PR, branch perpetua/SEC-batch-1).
  Closes SEC-02 (X-Tenant-Id trust) + SEC-05 (vault card get/delete/cryptogram/network-token IDOR) + SEC-06
  (marketplace payout/account/split) + SEC-19 (webhook DELETE IDOR) + SEC-20 (vault migration IDOR), across
  marketplace/vault/b2b/fraud/gateway. Mechanism routed through `common` (TenantPrincipal + CallerTenant.require()
  + TenantOwnership→ResourceNotFoundException 404, no existence oracle) — the iam principal can't be referenced
  from common-only modules (L-048). NO migration (tenant_id cols exist). 58 files. ADR-019, L-048.
- **SEC-BATCH-1b (HIGH) tenant scope: payment-lifecycle + ledger + webhook fan-out** — SEC-07 (get/capture/
  cancel/confirm/refund verify principal tenant owns the gateway id via ScreeningOriginService; incl. sub-threshold
  cross-tenant refund bypassing maker-checker) + SEC-08 (ledger journal queries add tenant scope) + SEC-09
  (webhook fan-out filter by event tenant UNCONDITIONALLY). gateway-api/ledger/payment-orchestration; T3 PR.
- **SEC-23 (HIGH) remaining X-Tenant-Id controllers** — found during SEC-BATCH-1 review (same defect class, were
  outside the verified scope + not in the original catalog): b2b B2bInvoiceController (getInvoice/sendInvoice/
  markInvoicePaid) + PurchaseOrderController + fraud FraudAssessmentController. Apply the SEC-BATCH-1 pattern
  (CallerTenant.require() + findByIdAndTenantId + TenantOwnership). Also: extend TenantIsolationIntegrationTest
  with real-SQL cross-tenant cases for the money + cryptogram paths (vendor approve, vault cryptogram, payout/
  split get). T3 PR. (chip task_b4c7fd92.)
- ~~**SEC-BATCH-2 (CRIT) dispute webhook**~~ — DONE 2026-06-15 (T3 PR). SEC-01 closed: fail-CLOSED HMAC-SHA512
  (constant-time, 401 on missing secret/sig) + idempotent openDispute on (tenant, external_dispute_id)
  (lookup-no-op + V4026 UNIQUE backstop; blank external id → 400) + server-authoritative tenant from the
  VERIFIED payload (X-Tenant-Id dropped) + the new dispute secret registered in StartupSecretsValidator
  (L-051). DisputeWebhookAuthReplayRedteamTest flipped into the gate (permanent SEC-01 guard). ADR-020.
- **SEC-24 (HIGH) dispute chargeback ledger posts under DEFAULT_TENANT** — found in the SEC-BATCH-2 money
  review: LedgerChargebackAdapter posts the DR chargeback_reserve / CR merchant_receivables under
  EnsureAccountsExistUseCase.DEFAULT_TENANT, not the dispute's (now server-authoritative) tenant — so a
  legitimate chargeback for merchant X reduces the DEFAULT tenant's receivables (money mis-attribution). Thread
  the dispute tenantId into the ledger posting (cross-module: dispute→ledger; verify per-tenant chargeback
  accounts + RLS). T3.
- **SEC-BATCH-3 (CRIT/HIGH) PCI/PAN** — SEC-03 (checkout iframe forged postMessage / STYLE_UPDATE overrides apiBase+sessionToken; frame-ancestors *) [origin half DONE in DX-1; residual: never accept apiBase/sessionToken via message + tighten frame-ancestors] + SEC-04 (full PAN persisted base64 in payment_tokens → route SDK tokenize through encrypted vault).
- **SEC-BATCH-4 (HIGH) money-dup/SSRF/reliability** — SEC-10 (ledger double-post: DB unique on idempotency) + SEC-11 (payout scheduler no lock → double-pay) + SEC-14 (outbound webhook SSRF allowlist) + SEC-16 (dead-letter stuck RETRYING) + SEC-13 (iframe-manager token leak — DONE in DX-1).
- **SEC-BATCH-5 (MED/LOW)** — SEC-12 (server-derived idempotency key for capture/void/refund) + SEC-15 (webhook dedup-before-commit) + SEC-17 (recon run.fail durable) + SEC-18 (analytics rollup idempotency) + SEC-21 (3DS origin — DONE in DX-1) + SEC-22 (float fee math, split idempotency, static master key, api-key prefix collision, /internal rate-limit bypass).

## Ready (sorted by score)

- ~~**B-006 | Run + record baseline security scans**~~ | DONE 2026-06-14 (CI-green).
  gitleaks + OSV-Scanner wired as CI jobs; semgrep Java SAST wired (sast-scan,
  pinned semgrep image) — REPORT-ONLY first (§15.3), CI runner CONFIRMED a real
  scan (100 rules / 703 files / 0 findings), then FLIPPED to BLOCKING with
  exit-code branching (fail on findings(1) AND broken scan(>=2), pass only on
  clean(0); set +e so the branch fires under bash -e — L-045). L-007/008/009
  re-verified; AUDITS recorded. See L-043/L-045. Residual: OSV is still
  report-only (`|| echo`) pending a triage of the first runner findings — flip
  to gating (high_vulns_max=0) once triaged.

- ~~**B-014 | Raise coverage on the lowest money/security modules**~~ | DONE 2026-06-14 (CI-green; commits 3b30bcc..ce2ff8d). ~272 MEANINGFUL behavior tests across billing (Price/Invoice/Proration/SmartRetry/InvoiceGeneration/Dunning), ledger (CreateJournalEntry/CreateFxConversion/GetBalance/BalanceReconciliation/FxGainLoss), gateway-api (RateLimit/Idempotency/WebhookDelivery/PaymentSession/Tokenization), common (Money/avro/exceptions) — hand-computed money math, full state machines, edge/error paths (not padding). Aggregate line coverage 16%->35% (5090/14530); test_count_floor 408->680, coverage_floor_pct 16->33. 7 CI iterations caught agent-test bugs + a REAL ledger NPE-on-null-event-type bug (now guarded). See L-042.

- ~~**B-012 | CI hardening: pin actions by SHA, add OSV + secret scan**~~ | DONE 2026-06-14 (CI-green).
  OSV + gitleaks were already gating jobs (B-006). This pinned ALL 12 `uses:` action
  refs across ci.yml + perpetua-gates.yml to full 40-hex commit SHAs (`# vX` comment),
  each independently re-resolved via `gh api .../commits/<tag>` (PIN-OK); the semgrep
  container is now DIGEST-pinned (`@sha256:...`), not just tag-pinned; gitleaks/osv
  binaries stay version-pinned. No floating tag/branch remains.

- ~~**B-015 | StripeCsvParser RFC-4180 violation**~~ | DONE 2026-06-14 (a36d10f..e1ecbeb, CI-green; ultracode workflow + adversarial review). Jackson CsvMapper RFC-4180 parse (quoted commas, "" escapes, embedded newlines, BOM); SettlementParserPort.parse() → ParseResult(records, failures); every unparseable/invalid row → a PERSISTED PARSE_ERROR ReconciliationException (no silent drop). Review caught + fixed: lone-quote no longer aborts the whole file (tokenizer error → recorded ParseFailure, records preserved); parse-failures persist in a REQUIRES_NEW tx (ParseFailureRecorder) so a downstream rollback can't erase them (L-039); column-count validation; logical-record-index lineNumber. ~33 aggressive tests. See L-039.

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

- ~~**B-022 | Reconcile approved-but-unexecuted refunds (stuck APPROVED)**~~ | DONE 2026-06-14 (T3 via PR; branch perpetua/B-022, CI-green). A @Scheduled RefundReconciler (gateway-api) discovers APPROVED-but-unexecuted refunds (new executed_at marker, migration iam/V4025) and RE-DRIVES executeApprovedRefund (never approve()) under the SAME deterministic refund-approval-<id> — the PSP Idempotency-Key collapses N submits to ONE refund (no double-pay). Fail-CLOSED Valkey lock (ADR-006; GatewaySchedulerLock since gateway-api can't depend on :billing); @SystemTransactional discovery + per-item TenantWorkRunner.callInTenant write (RLS WITH CHECK, L-035); bounded attempts + exponential backoff + locked operator-signal sweep; PSP `pending` is benign (no attempt increment, no false-page). T3 review 3 lenses all SHIP_WITH_NITS (0 blockers); nits fixed (V4024→V4025 collision, pending-handling, signal lock). See ADR-018, L-046.
- **B-022-async | async-refund settle path + max-pending-age signal** | T2 money | MEDIUM. The B-022 reconciler re-drives a `pending` PSP refund benignly (no attempt increment) and a later re-drive marks it executed once the PSP reports `succeeded` — but a refund stuck `pending` FOREVER is re-checked indefinitely without paging. Add a getRefund-poll (the adapter already has getRefund) or a refund webhook to flip executed_at on settle, + a max-pending-age operator signal so a never-settling refund is surfaced. Also fold in the Docker-gated @DataJpaTest proving the conditional-UPDATE SQL invariants (mark-once / discovery filters) — folds into B-016.

## Done
- **Premium checkout widget** (2026-06-15, FF) — implemented the deep-research design spec
  (docs/design/premium-checkout-spec.md; Stripe/Adyen/Checkout.com-tier). Fixed a REAL bug: the focus ring +
  tab tints used invalid `rgba(var(--token), a)` (renders nothing) → `color-mix` (L-050; proven via live
  Chromium computed styles). Extended tokens (navy-tinted soft shadows, spacing/type/easing/duration scales,
  new color tokens, full NIGHT dark-mode parity); two-layer :focus-visible ring; the ENCAPSULATED card-details
  group (Baymard 15-30% trust lift); REAL flat brand SVGs replacing the `<text>` placeholders; floating card +
  sticky summary/paybar; tabular-nums on amounts + PAN; motion easings + reduced-motion (parent+iframe) +
  forced-colors; 5 states identical across the PCI iframe seam; on-primary auto-derive that flips the button
  label to dark ink when a merchant brand color fails AA 4.5:1. 134 SDK tests green (+17). Seam parity,
  aria-invalid, blur-deferred instructional errors, iframe auto-resize. 3 reviews. No security behavior
  changed (DX-1 postMessage/3DS intact). RESIDUALS: appearance.fonts[] iframe passthrough (// TODO, P3);
  deterministic in-gate ledger soak variant.
- **Simulation / stress + red-team environment** (2026-06-15, FF) — an aggressive test harness, all
  REPORT-ONLY so it never reds the default gate (the SEC-* fixes are un-merged). (1) gatling load sims
  (payment burst / mixed-concurrency / refund-idempotency storm / dashboard read storm). (2) UNTAGGED in-gate
  soak/fuzz that passes on current main: ledger balance-invariant soak (de-flaked: low-contention + 40001
  retry), refund deterministic-key idempotency, HyperSwitch webhook guard, + parser/money fuzz (Money,
  StripeCsv, OFAC — partition-invariant / no-money-drop). (3) `@Tag("redteam")` attack suite EXCLUDED from
  the gate, run by a report-only `redteam-sim` CI job — genuine fail-on-vulnerable-main attacks: cross-tenant
  IDOR (seeded victims), dispute-webhook auth/replay, ledger double-post on redelivery, payout double-pay,
  outbound-webhook SSRF, PAN-in-payment_tokens (retargeted to the real B-004/SEC-04 path). 2 @Disabled with
  TODOs (need a stub PSP: idempotency-reuse → B-012/SEC-BATCH-5; payment-lifecycle IDOR → B-007/SEC-BATCH-3).
  Adversarial review killed 2 false-assurance tests + a gradle tag-filter no-op (redteamTest selected 0
  tests). Flip-plan in docs/simulation/README — each attack moves into the gate as its SEC-* fix lands. L-049.
  FOLLOW-UP: add vault delete/cryptogram + b2b vendor-approve + ledger-query + webhook-DELETE IDOR scenarios.
- **DX-1 | checkout-sdk verifier + postMessage/3DS origin hardening + OpenAPI + a11y** (2026-06-15, FF) —
  (1) NEW .github/workflows/checkout-sdk.yml: first CI verifier for the TS SDK (npm ci/build/test + report-only
  `npm audit --audit-level=high`; SHA-pinned actions); fixed the pre-existing BROKEN lockfile (missing
  packages/checkout → npm ci would have failed). (2) B-006 postMessage: send to exact computed origin (no '*'),
  receive checks event.origin first; parent origin pinned from BROWSER-ATTESTED window.location.ancestorOrigins
  (TOFU-on-event.origin only as Firefox fallback) — closes the PAN-exfil handshake spoof. (3) B-021 3DS handler:
  event.origin gate + https-host URL validation. (4) npm PR-A (picomatch/postcss/ws non-major). (5) OpenApiConfig
  @Bean (Info + 3 security schemes + server) + docs/api/openapi.html ReDoc. (6) prefers-reduced-motion across
  card-frame + checkout CSS. 109 SDK tests green. 3 adversarial-review iterations (L-047). RESIDUALS: B-023b npm
  PR-B (major toolchain: vitest/vite/esbuild/tsup — then flip the audit gate to blocking); ReDoc CDN not SRI-pinned;
  per-operation @SecurityRequirement for Swagger padlocks; same-origin hostile-parent is out of scope (page integrity).
- **Dead-code: TokenizationService multi-use path** (2026-06-14, FF) — removed the provably-dead
  `multiUseTokenExpiry` field + `@Value` ctor param + unreachable ternary branch (`singleUse=true` was
  hardcoded) and the now-unused `nexuspay.session.multi-use-token-expiry` key from application.yml.
  No request surface (TokenizeCommand) or caller expressed multi-use intent → REMOVE over WIRE (§7
  subtraction; re-add cleanly when SDK multi-use tokens are actually built). Single-use 15m tests intact.
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
- **CHARTER L1→L2** (2026-06-14) synced CHARTER.md (level L1→L2, STATUS DRAFT→RATIFIED, obsolete no-push/PR clause replaced with the L2 operating rule, North-star baseline 201→ratchets.json pointer) + CLAUDE.md operating core to the human-ratified L2 autonomy (Q-001). Human-gated carve-outs preserved: tier-3-via-PR, B-002-cutover flip, whitelisted_external_actions [], branch protection still pending (Q-002). ADR-016. (governance sync — unscored).

## Triaged from the first real CI scans (2026-06-10, B-006 now operational)
- **B-023 | checkout-sdk npm dev-dependency vulns** | T2 | OSV's first CI run (report-only, §15.3) flagged 7 vulns (1 Critical, 1 High, 5 Medium) — ALL in `checkout-sdk/package-lock.json` (the frontend SDK), ALL **dev** dependencies, NONE in the Java backend runtime: vitest 1.6.1→3.2.6 (9.8 Crit), picomatch→4.0.4 (7.5 High), vite→6.4.2, postcss→8.5.10, esbuild→0.25.0, ws→8.20.1. All have fix versions. Fix = bump checkout-sdk devDeps + re-test the SDK build, then flip the OSV gate from report-only to blocking (ratchets high_vulns_max=0). Logged in security/AUDITS.md.

## Triaged from the B-003 T3 dual review (2026-06-10) — pre-auth gate is a first cut
The fraud half is sound + tested; these are the gaps the adversarial + security
reviews found. The gate must NOT be relied on as a complete OFAC sanctions control
until B-025/B-026 land. See ADR-009.
- ~~**B-024 | Gate coverage**~~ | DONE 2026-06-10 (C1+C2, CI-green, T3-reviewed). @Primary GatedPaymentGateway at the PaymentGatewayPort boundary covers all callers (gateway/billing/workflow inherit it); confirm always screens + rejects flagged auto-capture; flow-aware (sanctions hard-block all; fraud BLOCK→REVIEW downgrade on server rails, capture+flag not hold). ADR-011.
- ~~**B-027 | REVIEW capture-hold enforcement + assessment linkage**~~ | DONE (C2). payment_capture_hold table (RLS) enforces the hold at capture + links payment→assessment. Residual: idempotent-fraud-on-retry (the velocity double-count part) folded into B-029.
- ~~**B-025 | Sanctions geography server-authoritative**~~ | DONE 2026-06-14 (CI-green; commits ba5b3fd→f007ed9). `ServerGeographyResolver` derives destination from the trusted `merchant_country` (new `V4011` column on merchant_currency_prefs) + source from a trusted edge signal; client request metadata is ADVISORY only. Unknown country on a cross-border-capable flow → REVIEW/EDD (never silent ALLOW), and that compliance REVIEW is NON-DOWNGRADEABLE on server rails via `GateDecision.mandatoryReview` (honored in create+confirm regardless of ScreeningMode; fraud-REVIEW server-rail policy preserved). See ADR-014.
- ~~**B-026 | OFAC list parser + fail-closed + health**~~ | DONE 2026-06-14 (CI-green). Parser re-derived against the REAL CSL feed (ISO-2 at the tail of each `;`-separated `addresses` entry + nationalities/citizenships, scoped to comprehensive-embargo `programs`, unioned with a curated KP/IR/SY/CU baseline) — the old code targeted a non-existent `countries` column and silently kept the static 4 forever. Faithful 29-col fixture + parser tests; feed URL now injectable (no live-network in tests). `CrossBorderComplianceService` BLOCKS when screening is unavailable (empty/stale/failed); a `sanctions` readiness HealthIndicator pulls a degraded screen from rotation; boot-with-static-baseline is healthy. Security re-review READY. See ADR-014, L-040.
- **B-026-hardening | OFAC robustness residuals (from the re-review)** | T2 security | LOW–MEDIUM. SHOULD_FIX items deferred (all have safe defaults/safety-nets): (a) the live feed's `SYRIA` program token is retired → SY is held only by the curated baseline, and `CAATSA - IRAN/RUSSIA` tokens miss the prefix map (harmless via baseline) — track feed-coverage and WARN when a baseline ISO-2 isn't confirmed by any feed row; (b) region tagging (Crimea/DNR/LNR) is largely inert because real region addresses carry a trailing `UA` ISO-2 — also run the region scan when a UA tail is present (audit-only, no fail-open); (c) the `nexuspay.fx.compliance.unknown-geo-review-enabled` kill-switch can downgrade unknown-geo REVIEW to ALLOW if flipped false — given the project's .env-override history, log a startup WARN when disabled + a guard test (or remove the switch); (d) `ofacAvailable` can flip true on a content-empty-but-parseable feed (baseline union) — track feed-contributed count separately. See the B-025/026 re-review.
- **B-027 | REVIEW capture-hold enforcement + assessment linkage** | T3 | MEDIUM. (a) REVIEW only flips `capture_method=manual` on create; persist (paymentId→assessmentId, PENDING_REVIEW) and refuse capture/confirm while PENDING_REVIEW. ~~(b) idempotent fraud~~ DONE as B-027b (folded into B-029, commit cef19ab — dedup on (tenant, idempotency key) + unique-index backstop). (c) write the real gateway payment id back onto the assessment (currently keyed by the preauth ref).
- **B-028 | Fraud fail-open posture + adjacent hardening** | T2 | LOW/MEDIUM. Document + alert the fraud fail-open posture (FRM-down + sparse context → ALLOW; consider REVIEW-not-ALLOW on higher amounts when FRM unavailable). Stop echoing the matched country in the `cross_border_blocked` 403 body (keep in logs). `FraudAssessmentController` review endpoints trust a spoofable `X-Tenant-Id` header. PAN-in-domain-object: `PaymentRequest.paymentMethodData` carries a raw PAN — add a masking `toString` + logging filter (PCI). Normalize geo-rule config codes (`trim().toUpperCase()`).

## Triaged from the B-024 T3 review (2026-06-10) — port-boundary gate
- ~~**B-029 | Gate mode + tenant server-authoritative at the port boundary**~~ | DONE 2026-06-14 (CI-green; commit cef19ab). A server-set `CallContext(tenantId, mode)` is threaded through `PaymentGatewayPort` (additive overloads); `GatedPaymentGateway` takes mode+tenant ONLY from CallContext (REST=interactive(principal.tenantId), billing=serverRecurring, workflow=serverOther) and scrubs client source/workflow/tenant_id markers; confirm authority comes from a server-owned `payment_screening_origin` table (V4022), never intent metadata; the 1-arg createPayment forces a STRICT default (INTERACTIVE + null tenant). Security review PASSED. Also folded in **B-027b** (idempotent fraud): assess() dedups on (tenant, idempotency-key) + a unique index (V4023, dedups-before-create) as the concurrent-race backstop via saveAndFlush; velocity SET-NX prevents double-counting. See ADR-015, L-041.
- ~~**B-029-hardening | request-fingerprint on a fraud dedup hit**~~ | DONE 2026-06-14 (T3 via PR, CI-green; branch perpetua/B-029-hardening). A dedup hit now recomputes a KEYED HMAC-SHA256 request fingerprint (domain-separated `fraud-request-fingerprint` key from the vault master key; injective length-prefixed/type-tagged/null-marked canonicalization — no delimiter-injection forgery; tenant-self-binding, TAG_VERSION 0x02) and compares constant-time: match→short-circuit; MISMATCH (key reused for a different charge)→RE-ASSESS + WARN, return fresh decision WITHOUT a 2nd row (unique-key violation structurally unreachable); legacy null→prior. Fail-CLOSED (fp computed before lookup; compute/key error rolls back, never serves a stale ALLOW). `fraud_assessments.request_fingerprint` VARCHAR(64) via V4024 (next free global, ADD COLUMN IF NOT EXISTS). T3 review: crypto/migration/dedup-bypass SHIP; test-adequacy BLOCKED on VACUOUS anti-collision tests (a pipe-join mutant kept them green) → fixed + mutation-verified. See ADR-017, L-044.
- **B-029-velocity | velocity accounting keyed on charge identity, not the idempotency key** | T3 fraud | MEDIUM. Found in the B-029-hardening crypto review (SHOULD_FIX): the rule pipeline gates the velocity INCR behind a SET-NX first-seen marker keyed on (tenant, idempotency-key). On the key-REUSE re-assess path the original request already set that marker, so N distinct charges under one reused idempotency key only count the FIRST toward velocity — partial velocity-rule evasion. The different charge still gets a fresh correct decision (the B-029-hardening goal holds), but velocity is under-counted. Fix: key velocity on charge identity / the request fingerprint, or force-INCR when reuseDetected. Own T3 review + tests.
- **B-030 | Server-rail fraud-flag review event/queue + capture-hold lifecycle** | T2 | MEDIUM. A server-rail (billing/workflow) fraud REVIEW now captures + logs (ADR-011 M1 fix) but emits no durable analyst signal — add a `payment.review_required` event + an analyst queue/release path. Also: voidPayment leaves an orphaned HELD capture-hold row (transition to terminal on void); `release()` is a silent no-op on missing id; correlate the create-vs-confirm assessment refs. (Review LOW items L2/L3/L4/N5.)

## B-002 RLS runtime activation (remaining — staged, co-lands with the human cutover)
- ~~**B-002-activation | tx-manager + system routing + job routing + FORCE (C5-C7)**~~ | DONE 2026-06-13 (dormant, CI-green; commits 719ee55 + f06d483). The machinery is LANDED behind `rls.enforce=false`: single EMF over a `RoleRoutingDataSource` (app/system Hikari pools) + `RlsRoutingTransactionManager.doBegin` set_config GUC; `@SystemTransactional` (relocated to `io.nexuspay.common.rls`) + `SystemRoleAspect` thread-local role pin; the 16 genuinely cross-tenant @Scheduled jobs annotated SYSTEM (15 + TrialExpirationScheduler added in the post-ship review); broken `TenantAwareDataSourceConfig` deleted; `R__rls_force_owner.sql` repeatable FORCE (gated by `${rlsforce}`, default false); `analytics/V3022` normalizes the 7 analytics policies to the fail-closed `current_tenant_id()` helper (review #3). Proven by RlsDormancyIT (everything absent + no table forced at enforce=false) + RlsEnforceIT (boots on nexuspay_app under the `rls-enforce` profile: tenant isolation, fail-closed-unbound, @SystemTransactional bypass, every RLS table FORCE'd, analytics policies fail-closed). Post-ship adversarial review = SHIP-WITH-MINORS (0 blocker/0 must-fix; 8 minors fixed/documented). See ADR-012 (+addendum), L-033/34/35.
- ~~**B-002-activation-tenant | bind TenantContext on the 6 consumers + per-item billing writes**~~ | DONE 2026-06-14 (dormant, CI-green via PR #2; commits 79937ea→6cfebc3). Shared `TenantWorkRunner` (interface in `common`; `AppTenantTransactionTemplate` enforce=true / `InlineTenantWorkRunner` dormant, in `app`): `runInTenant`/`callInTenant` open a REQUIRES_NEW APP+tenant tx; `bindTenant` binds APP+tenant WITHOUT an enclosing tx (so an inner @Transactional keeps its own isolation — L-036). The 6 consumers bind before their tx (ledger uses bindTenant to preserve its SERIALIZABLE journal write; analytics/billing drop @Transactional + route through `runInTenant(metadata.tenant_id?:"default")`; gateway loads endpoints tenant-scoped, gated on the enforce flag — L-037 — HTTP loop outside the tx; fraud = comment). The 3 billing sweeps run discovery under the SYSTEM pin then each per-item write via the helper on APP+tenant. Proven by the helper enforce-IT (APP+tenant write/read/WITH-CHECK/SYSTEM-nesting) + an analytics-consumer enforce-IT (bind→WITH-CHECK write) + the dormancy IT (InlineTenantWorkRunner active, AppTenantTransactionTemplate absent). Adversarial review = SHIP-WITH-FIXES → both fixes applied. See ADR-013, L-036/037/038. REMAINING (deferred to B-002-cutover, documented): (a) the per-event tenant on the `nexuspay.payments` topic — Step 0 below; (b) deferred ITs — gateway (RestClient/HTTP coupling), the 3 sweeps (heavy billing fixtures), routing/ledger consumers (redundant with the analytics+helper ITs).
- **B-002-cutover Step 0 | stamp the real tenant on `nexuspay.payments`** | T3 | MANDATORY PRE-FLIP. `HyperSwitchWebhookController` publishes outbox events with NO tenant (metadata={source,original_event_id}; the 4-arg OutboxEvent ctor hardcodes tenant_id="default"), so all 4 payments-topic consumers bind "default" under enforcement → real-tenant rows are invisible (silent no-op / fail-closed). Resolve the merchant→tenant mapping at HS ingest, stamp `metadata.tenant_id` + use a tenant-aware OutboxEvent ctor; optionally add a `tenant_id` Kafka header in OutboxRelay (enables a future thin RecordInterceptor). Do NOT flip prod `enforce=true` until this lands. Then write the deferred consumer/sweep ITs against real-tenant fixtures.
- **B-002-cutover | the human-gated flip itself** | T3 security | Provision the real `nexuspay_system` + `nexuspay_app` secrets (out-of-band ALTER ROLE), then in staging→canary→fleet: set the app role to `nexuspay_app`, `rls.enforce=true`, and (paired) `spring.flyway.placeholders.rlsforce=true` + bump `R__rls_force_owner.sql` so Flyway re-applies FORCE. Pre-req: B-002-activation-tenant MUST be green first. Rollback: enforce=false (app reverts to owner) and/or rlsforce=false + file bump (NO FORCE). The RlsEnforceIT is the pre-flip gate. Resolves review M2 (capture-hold GUC visibility) once consumers bind tenant.
