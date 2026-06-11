# RFC B-002 — Make RLS actually enforce at runtime (CONFIRMED two-part bug)

CONFIDENCE: high on root cause (read TenantAwareDataSourceConfig + V2001).
T3 security. MUST be verified against a real Postgres with the RLS policies — a
wrong fix either leaks cross-tenant data OR blocks every row. Do NOT apply blind.

## Root cause (both must be fixed)
1. **`SET LOCAL` outside a transaction = no-op.** `TenantAwareDataSource.getConnection()`
   runs `SET LOCAL app.current_tenant_id = ?` the instant the connection is checked
   out — before Spring/Hibernate opens the transaction. In autocommit, `SET LOCAL`
   is scoped to the implicit single-statement tx and is discarded immediately, so by
   the time the real query runs the GUC is unset. The class comment claims tx-scoping
   but the timing defeats it.
2. **App connects as the table owner.** The owner BYPASSES RLS unless
   `FORCE ROW LEVEL SECURITY` is set. V2001 created a `nexuspay_app` role meant to be
   subject to RLS, but the datasource still authenticates as the owner/superuser, so
   policies never apply even if the GUC were set.

## Recommended fix
- **Set the GUC inside the transaction, transaction-locally:** replace the
  on-checkout `SET LOCAL` with a per-transaction initializer that runs
  `SELECT set_config('app.current_tenant_id', ?, true)` (the `true` = local to the
  current transaction) on the actual transactional connection. Options, in order of
  preference: (a) Hibernate `tenant`-style hook / a `@Transactional` AOP aspect that
  executes set_config at tx start; (b) a `TransactionSynchronizationManager`
  registration from `TenantContextFilter` that initializes the GUC when the tx
  binds. Keep `nexuspay.multi-tenancy.rls.enabled` flag; default-on only once verified.
- **Connect as the non-owner `nexuspay_app` role** (subject to RLS) for app traffic;
  keep owner/superuser only for Flyway migrations (separate datasource or Flyway user).
  Alternatively `ALTER TABLE … FORCE ROW LEVEL SECURITY` so even the owner is bound.
- **Fail-closed:** if no tenant is bound on a tenant-scoped request, the query must
  return zero rows (RLS with a non-owner role does this), not silently bypass.

## Verification (the gate)
`app/src/test/.../RlsIsolationIT` (Testcontainers Postgres): create rows for tenant
A and B; bind tenant A; assert queries see ONLY A's rows and that a cross-tenant
update/select for B affects nothing; assert no-tenant-bound → zero rows (not all).
Self-skips without Docker; runs in CI. This is the proof the fix works and the
guard against regressing to silent bypass.

## Why not blind-applied
No Postgres here to confirm the GUC actually scopes per-tx, the role is RLS-bound,
and policies match the column. Shipping unverified RLS is worse than the current
known-broken state (false sense of isolation). Execute under Docker/CI with the IT
above as the acceptance gate.

## STATUS 2026-06-10 — acceptance gate PROVEN; production activation scoped + deferred
`RlsIsolationIntegrationTest` (app module) is GREEN in CI: connecting as the
non-owner `nexuspay_app` role with the GUC set, the V2001 policies isolate tenant A
from B, a cross-tenant delete affects 0 rows, and no-tenant-bound returns ZERO rows
(fail-closed). So **the policies + set_config mechanism are correct** — confirmed,
not assumed. The remaining gap is purely that the running app authenticates as the
table OWNER (NEXUSPAY_DB_USER=nexuspay), which bypasses RLS.

ACTIVATION PLAN (deliberate, staged — NOT a single blind flip):
1. **GUC timing.** Replace the on-`getConnection` `set_config(...,true)` (discarded in
   autocommit) with either (a) Hibernate `MultiTenantConnectionProvider.getConnection
   (tenant)` running `set_config('app.current_tenant_id', tenant, false)` and
   `releaseConnection` RESETting it (canonical, no pool leak), or (b) the existing
   DataSource decorator fixed to set session-scope at checkout + RESET on the wrapped
   `Connection.close()` before return to the pool. Prefer (a).
2. **Non-owner role for app traffic.** Point the JPA datasource at `nexuspay_app`
   (NEXUSPAY_DB_USER); keep the OWNER only for Flyway (`spring.flyway.user/password`)
   so migrations still create/alter under the owner.
3. **Cross-tenant background jobs (THE architectural decision — 16 @Scheduled jobs:**
   RenewalScheduler, TrialExpirationScheduler, PayoutScheduler, OutboxRelay,
   DeadLetterReprocessor, BalanceReconciliationJob, analytics rollups/retention,
   FxRateLock/Streaming, DataRetentionJob, OutboxLagMonitor, SanctionsListAdapter).
   Under a non-owner role these see zero rows unless handled. Two options — pick ONE
   in an ADR before coding:
   - **(A) system-tenant policy bypass:** extend every tenant policy to
     `USING (tenant_id = current_tenant_id() OR current_tenant_id() = 'system')`
     (TenantContext.SYSTEM_TENANT already exists) and have jobs bind 'system'. Simple,
     one datasource; WEAKER (a GUC-manipulation/SQL-injection bug → full bypass).
   - **(B) separate owner/system datasource** used only by the cross-tenant jobs.
     No policy escape hatch (stronger); more infra (2nd pool + routing).
4. **Verify:** RlsIsolationIntegrationTest (extend with a system-tenant case for whichever
   option) + the FULL integration suite + a deliberate check that each of the 16 jobs
   still returns rows. Roll out behind `nexuspay.multi-tenancy.rls.enabled` with
   canary/monitoring — a wrong flip leaks tenants OR returns zero rows app-wide.
WHY DEFERRED: steps 2–3 are high-blast-radius and gated on a security architecture
decision (A vs B); rushing them at session-end risks app-wide breakage or a leak —
worse than the current (known, documented) state. The correctness is now PROVEN; the
rollout is a focused, staged follow-up.
