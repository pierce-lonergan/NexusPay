# Handoff — 2026-06-13 — session 3 (B-002 RLS runtime machinery C5-C7 DONE, dormant)
NOW: **CI green** (CI + perpetua-gates) on perpetua/bootstrap / PR #1. App boots end-to-end on
real Postgres/Kafka/Redis; **296 tests** pass (floor 285→289). Ran ultracode (a design-verification
workflow + a 26-method job-classification workflow with per-method adversarial verification).
ACTIVE ITEM: none in-flight | branch perpetua/bootstrap | phase SELECT
LATEST commits: 719ee55 (C5 enforce-IT secrets fix) · f06d483 (C6 job routing + C7 FORCE).

DONE (this session): **B-002 RLS runtime activation machinery C5-C7 — LANDED DORMANT, CI-green.**
The feature is fully built and proven, gated behind `nexuspay.multi-tenancy.rls.enforce=false`;
the prod flip stays human-gated. See ADR-012, L-033/34/35.
- **C5 (mechanism):** single EMF over a `RoleRoutingDataSource` (app/system Hikari pools keyed by
  ThreadLocal `DbRoleContext`, default APP=fail-closed); `RlsRoutingTransactionManager.doBegin` runs
  `set_config('app.current_tenant_id',?,true)` for APP txns, skips for SYSTEM. `RlsEnforcementConfig`
  contributes the @Primary DataSource + tx-manager ONLY at enforce=true. Deleted the broken
  `TenantAwareDataSourceConfig` (set_config at getConnection in autocommit = RLS-inert).
- **C6 (job routing):** `@SystemTransactional` relocated to `io.nexuspay.common.rls` (so all 7 modules
  can declare it; the `SystemRoleAspect` stays in `app` and advises by annotation type across the
  context, pinning SYSTEM via a call-scoped thread-local that covers the whole synchronous subtree).
  Applied to the **16 genuinely cross-tenant @Scheduled jobs** (15 + TrialExpirationScheduler added
  per review finding #2) (analytics rollups/retention/MV ×5,
  app DLQ reprocessor, billing renewal+dunning ×2, ledger reconcile, marketplace payouts, obs
  outbox-lag, payment retention ×2 + outbox relay + fx-lock). 6 single-tenant Kafka consumers were
  deliberately NOT annotated; 5 NO_DB jobs need nothing.
- **C7 (FORCE owner):** `R__rls_force_owner.sql` repeatable, idempotent both ways, gated `${rlsforce}`
  (default false = no table forced → app-as-owner never locked out). application.yml defaults it
  false; application-rls-enforce.yml flips it true.
- **Proof:** RlsDormancyIT (enforce=false → routing DS/aspect/config absent AND zero tables forced)
  + RlsEnforceIT (boots on nexuspay_app under the `rls-enforce` profile: tenant A↔A / B↔B isolation,
  unbound=zero rows fail-closed, @SystemTransactional sees all, every RLS table FORCE'd).

STATE: CI green; 296→297 tests (floor 289); coverage floor 16. PR #1 mergeable. A post-ship
adversarial review (ultracode, 5 dimensions + per-finding verification) returned SHIP-WITH-MINORS
(0 blockers/0 must-fix, 8 confirmed minors) — all fixed or documented: TrialExpiration annotated
(→16 jobs), analytics policies made fail-closed (V3022), the R__ re-trigger runbook corrected
(placeholder flip alone re-runs; no file bump), DLQ async-pin + superuser-FORCE caveats documented.
See ADR-012 addendum. Findings #4 (FORCE/enforce interlock = profile coupling) and #6 (cross-module
routing assertion) are documented residuals folded into B-002-activation-tenant.

### ⚠ B-002 CUTOVER CHECKLIST (human-gated; do in order — see BACKLOG B-002-activation-tenant / B-002-cutover)
The machinery is dormant. Before flipping enforcement in prod, these MUST be green first:
1. **B-002-activation-tenant (MANDATORY PRE-FLIP):** the 6 single-tenant consumers never bind
   TenantContext, so they fail-closed (break) at the flip. Bind tenant from the event (try/finally),
   stay on APP (NOT @SystemTransactional), add an IT each under the rls-enforce profile:
   - ledger `PaymentEventConsumer.onPaymentEvent` (posting INSERT + idempotency read → DLT)
   - analytics `PaymentEventAnalyticsConsumer.consume`, `RoutingEventAnalyticsConsumer.consume`
   - billing `BillingPaymentEventListener.onPaymentEvent` (invoices stop PAID; dunning stops)
   - gateway-api `WebhookDeliveryService.onPaymentEvent` (ALSO add @Transactional + tenant-scoped finder)
   - analytics `FraudEventAnalyticsConsumer` (NO_DB today; pre-bind when it gains a write)
   PLUS billing batch sweeps (RenewalScheduler doProcessRenewals/processDunning,
   TrialManagementService.convertExpiredTrials): keep the DISCOVERY scan SYSTEM but bind TenantContext
   per-subscription for the per-item WRITES so WITH CHECK still guards them (split the cross-tenant tx
   into per-tenant txns). DeadLetterReprocessor async whenComplete saves run off the SYSTEM pin
   (harmless until RLS is added to dead_letter_queue).
2. **B-002-cutover:** provision real nexuspay_app + nexuspay_system secrets out-of-band; then
   staging→canary→fleet: app role→nexuspay_app, `rls.enforce=true`, and (paired) flip
   `spring.flyway.placeholders.rlsforce=true` AND bump `R__rls_force_owner.sql` so Flyway re-applies
   FORCE. Rollback: enforce=false (reverts to owner) and/or rlsforce=false + file bump (NO FORCE).
   RlsEnforceIT is the pre-flip gate. Resolves review M2 (capture-hold GUC visibility) once consumers bind tenant.

WATCH OUT (carried forward):
- **B-029 (HIGH, latent):** gate mode + tenant from request metadata; safe today (callers stamp
  server-side) but ties into the per-job tenant binding above.
- **B-025/B-026 still open:** sanctions geography client-metadata-derived; OFAC CSV parser broken
  (4-country static fallback). Do NOT advertise OFAC coverage.
- Benign log noise: under the enforce IT, @Scheduled jobs (e.g. OutboxRelay.relayEvents) log a
  `FATAL: postmaster exit` at Testcontainer teardown — a teardown race, not a failure (CI green).
- BUILD: JDK 21 + TMP=C:\Temp, then .\gradlew.bat. No Docker locally AND the Gradle daemon can't
  fork its JVM in this sandbox (loopback) → ALL verification is via CI; diagnose via the
  `test-results` artifact (JUnit HTML has the real stack/SQL).
- B-022 (stuck-APPROVED refund) open. Q-002/Q-006 open. CHARTER still L1 (update to L2).

BUDGET: heavy (two ultracode workflows + a 4-agent annotation fan-out + multi-module change). limit: ok.
QUEUE (pick by value): B-002-activation-tenant (the pre-flip gate, T3, HIGH) · B-029 (gate authority) ·
B-025/B-026 (sanctions authority + OFAC parser) · B-030 (review event) · B-014 (coverage) ·
B-023 (checkout-sdk npm vulns) · CHARTER L1→L2.
