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
