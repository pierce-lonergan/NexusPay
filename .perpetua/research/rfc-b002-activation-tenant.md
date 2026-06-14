# RFC B-002-activation-tenant — bind TenantContext on the 6 consumers + per-tenant billing writes

Source: grounded ultracode design workflow (4 investigators + synthesis), 2026-06-14. Builds on the
landed C5-C7 machinery (ADR-012). All steps land DORMANT at `rls.enforce=false` and are proven by
enforce-profile ITs. The prod flip stays human-gated (B-002-cutover).

## Locked decisions
- **Helper = interface in `common`, impl in `app`** (forced by the module graph: only `app` sees both
  `DbRoleContext` (app) and `TenantContext` (iam); billing/ledger/analytics/gateway see only `:common`).
  Mirrors `@SystemTransactional` (marker in common, aspect in app).
- **Sweep shape = discovery (SYSTEM) COMMITS FIRST, then per-item write in its OWN `REQUIRES_NEW` APP tx**
  bound to that item's tenant — never hold a SYSTEM connection across N synchronous PSP charges.
- **Consumer binding = per-consumer payload extraction**, NOT a RecordInterceptor: `OutboxRelay` puts only
  `{event_type, aggregate_type, aggregate_id}` on the wire — no tenant header exists yet (a uniform header
  is a future optimization, Step 0-optional). Tenant lives at inconsistent JSON paths per topic.
- **`TenantContext` MUST be bound BEFORE the `@Transactional` boundary** (`RlsRoutingTransactionManager.doBegin`
  reads it at tx-begin). So: non-transactional listeners wrap in place; `@Transactional` listeners drop the
  annotation and delegate through the helper (its `REQUIRES_NEW` becomes the boundary).
- **Self-invocation is sidestepped**: the helper's tx comes from a programmatic `TransactionTemplate`, not an
  AOP proxy — so a same-bean per-item call still gets a real tx (no private-method/self-call trap).

## STEP 0 (prerequisite — NOT in this task; tracked under B-002-cutover)
`nexuspay.payments` carries NO tenant today (`HyperSwitchWebhookController` omits it → DB row `tenant_id="default"`).
So consumers on that topic bind `"default"` until Step 0 resolves the merchant→tenant mapping at HS ingest and
stamps `metadata.tenant_id` + a tenant-aware `OutboxEvent`. This task builds+proves the MECHANISM (ITs inject a
real tenant); **do NOT flip prod `enforce=true` until Step 0 lands.** Keep the `"default"` fallback meanwhile
(fail-closed is the safe failure mode).

## 1. Helper (DONE in increment 1)
- `common/.../rls/TenantWorkRunner.java` — `void runInTenant(String,Runnable)` + `<T> T callInTenant(String,Supplier<T>)`.
- `app/.../rls/AppTenantTransactionTemplate.java` (@ConditionalOnProperty enforce=true): `TransactionTemplate`
  REQUIRES_NEW; capture+set `DbRole.APP` + `TenantContext` before `execute`, restore in finally.
- `app/.../rls/InlineTenantWorkRunner.java` (enforce=false, matchIfMissing): plain REQUIRES_NEW, ignores tenant
  — keeps tx structure byte-identical in both modes (this is what keeps the feature dormant).
- `DbRoleContext.set(DbRole)` added (template needs interleaved role/tenant save-restore).

## 2. Consumers (6)
- Pattern A (listener NOT @Transactional → wrap in place): ledger `PaymentEventConsumer.onPaymentEvent`,
  gateway `WebhookDeliveryService.onPaymentEvent`.
- Pattern B (listener IS @Transactional → drop it, delegate through helper): analytics
  `PaymentEventAnalyticsConsumer`, `RoutingEventAnalyticsConsumer`, billing `BillingPaymentEventListener`.
- Tenant source: `metadata.tenant_id` (fallback `"default"`); ledger uses its existing `tenantOf(event)`.
- gateway also: replace `findAllByEnabledTrue()` → existing `findAllByTenantIdAndEnabledTrue(tenant)` (fixes a
  pre-existing cross-tenant webhook fan-out leak); load endpoints inside the tenant tx, POST loop OUTSIDE it.
- analytics `FraudEventAnalyticsConsumer`: NO DB today — comment only (bind when it gains a write).

## 3. Billing sweeps (3) — discovery SYSTEM, per-item APP+tenant via helper
- `RenewalScheduler.doProcessRenewals` (lightest): extract per-sub body into a processOne unit; loop
  `tenantWork.runInTenant(sub.getTenantId(), () -> processOne(sub))` inside the existing per-item try/catch.
- `DunningService.processPendingAttempts`: drop @Transactional (discovery+loop only); route each
  `processAttempt(attempt)` through the helper keyed on `attempt.getTenantId()`.
- `TrialManagementService.convertExpiredTrials`: drop @Transactional; per-item `convertOne(sub)` via helper
  keyed on `sub.getTenantId()`.
- Atomicity change (desired): per-item commits independently; one tenant's failure doesn't roll back others
  (renewal already behaves this way — this aligns all three).

## 4. Tests — enforce-profile ITs in `app` (model on RlsEnforceIntegrationTest)
Helper IT; ledger/analytics/billing/gateway consumer ITs; renewal/dunning/trial sweep ITs; each asserts BOTH
bound→write-succeeds-isolated AND unbound/mis-bound→fail-closed. Extend RlsDormancyIT: at enforce=false the
active TenantWorkRunner is InlineTenantWorkRunner and AppTenantTransactionTemplate is absent.

## 5. Order / risk
Helper → ledger consumer → analytics/billing/gateway consumers → sweeps (renewal→dunning→trials). Highest risk =
money-tx semantics of dunning/trials (per-item commit isolation; the per-item try/catch is load-bearing).
Open checks during coding: a `'default'` tenant row exists; no class-level @Transactional on the billing repo
adapters that would create a loop-spanning SYSTEM discovery tx; pool sizing under enforce (canary).
