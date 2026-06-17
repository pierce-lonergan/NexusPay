# Runbook — B-002 Row-Level-Security production cutover

**Status: DORMANT. Activation is HUMAN-GATED. Do not flip in production without completing the
blocking preconditions below.** This runbook documents how an operator activates the multi-tenant
Postgres Row-Level-Security (RLS) isolation that is fully built but inert behind a flag.

The RLS *machinery* (B-002 C4–C7: roles, policies, the role-routing datasource, the per-transaction
tenant GUC, the `FORCE` migration, and the `@SystemTransactional` bypass for cross-tenant jobs) is
complete and proven by integration tests. Activation is a config + ops action, not a code change.

> **What RLS does — and the one thing it does NOT do.** RLS makes the database return only rows
> whose `tenant_id` matches the tenant the application *bound* for the current transaction. It is a
> defense-in-depth backstop **underneath** the application's tenant authority — it is **not** a
> substitute for it. If a request path binds an *attacker-chosen* tenant (e.g. read from a client
> `X-Tenant-Id` header), RLS will faithfully isolate to the **attacker's** chosen tenant and hand
> over the victim's rows. **The tenant must be derived from server authority (the authenticated
> principal) before RLS can protect anything.** See blocking precondition #1.

---

## 1. Blocking preconditions (must ALL be true before cutover)

### 1.1 — SEC-26: every authorization path must derive the tenant from the principal, not a header  ✅ CLOSED

RLS only isolates correctly if the bound tenant is server-authoritative. All known paths now are:
SEC-1b (payment-lifecycle / ledger-query / webhook fan-out), SEC-23 (b2b + fraud), INT-1/INT-3
(canonical webhook + sandbox), and **SEC-26** (analytics `AnalyticsController` + billing
`{Invoice,Product,Subscription}Controller`) — all derive the tenant from
`common.tenant.CallerTenant.require()` / `TenantContextFilter` reading `NexusPayPrincipal.tenantId()`,
with by-id reads/writes tenant-scoped via `findByIdAndTenantId` + `TenantOwnership` (404, no oracle).
SEC-26 also closed a secondary cross-tenant input (a client-supplied `priceId` reaching an unscoped
price lookup in `createSubscription`/`changePlan`; see ADR-041 / L-059).

**Verification — re-run in CI; must return only comments, never a live `@RequestHeader("X-Tenant-Id")`
in an authorization path (a new one silently reopens the hole RLS cannot catch):**
```bash
grep -rn '@RequestHeader.*X-Tenant-Id' --include='*.java' */src/main   # expect: none
```

### 1.2 — Database role passwords are set in production

The migrations create `nexuspay_app` and `nexuspay_system` with **dev-only** passwords
(`nexuspay_app_local`, `nexuspay_system_local`). Before cutover, set strong production passwords as
the DB owner and store them in your secrets manager:
```sql
ALTER ROLE nexuspay_app    WITH PASSWORD '<strong-random-secret>';
ALTER ROLE nexuspay_system WITH PASSWORD '<strong-random-secret>';
```

### 1.3 — No tenant-scoped table is missing a policy

Any table with a `tenant_id` column added since the last RLS migration must have a policy, or its
rows leak across tenants once the app runs on the non-owner role. Audit before cutover:
```sql
-- Tables that have a tenant_id column but NO RLS policy → must be fixed before cutover.
SELECT c.relname
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND a.attnum > 0
WHERE c.relkind = 'r' AND n.nspname IN ('public','analytics')
  AND NOT EXISTS (SELECT 1 FROM pg_policies p WHERE p.schemaname = n.nspname AND p.tablename = c.relname);
```

### 1.4 — The dormancy + enforcement integration tests are green in CI

- `app/.../RlsDormancyIntegrationTest` — proves the default (`enforce=false`) is byte-identical to
  pre-RLS (single owner datasource, no forced tables).
- `app/.../RlsEnforceIntegrationTest` — under the `rls-enforce` profile, proves cross-tenant rows are
  invisible on the app role, writes are blocked by `WITH CHECK`, unbound transactions fail **closed**
  (zero rows, not an error), `@SystemTransactional` bypasses, and every RLS table is `FORCE`d.

---

## 2. How it is wired

| Concern | Mechanism |
|---|---|
| Master switch | `nexuspay.multi-tenancy.rls.enforce` (`NEXUSPAY_RLS_ENFORCE`), default `false`. When `false`, `RlsEnforcementConfig` contributes **zero** beans → stock single-owner datasource. |
| Profile | `application-rls-enforce.yml` (activate with `SPRING_PROFILES_ACTIVE=...,rls-enforce`). Sets `enforce=true` **and** the Flyway placeholder `rlsforce=true` together. |
| App pool | `nexuspay_app` — RLS-bound, default route, fail-closed. |
| System pool | `nexuspay_system` — `BYPASSRLS`, used only by `@SystemTransactional` cross-tenant jobs. |
| Routing | `RoleRoutingDataSource` (@Primary) selects pool by a thread-local `DbRole` (defaults to APP). |
| Tenant binding | `RlsRoutingTransactionManager.doBegin()` runs `set_config('app.current_tenant_id', <tenant>, true)` per APP transaction (transaction-local → no pool leak). Unbound APP tx ⇒ GUC NULL ⇒ `current_tenant_id()` returns NULL ⇒ zero rows. |
| Cross-tenant jobs | `@SystemTransactional` (in `:common`) + `SystemRoleAspect` pin the SYSTEM (BYPASSRLS) role for the ~16 sweep/relay/rollup/reconciler jobs; per-item writes re-bind APP+tenant via `TenantWorkRunner` so `WITH CHECK` still validates each write. |
| Owner lockout guard | `R__rls_force_owner.sql` (repeatable, gated by `${rlsforce}`): `true` ⇒ `FORCE ROW LEVEL SECURITY` on every RLS table; `false` ⇒ `NO FORCE` (so the owner is never locked out while dormant). Flipping the placeholder changes the checksum and re-runs it — no file edit. |

### Required production environment variables
```
NEXUSPAY_RLS_ENFORCE=true
SPRING_PROFILES_ACTIVE=<env>,rls-enforce
NEXUSPAY_DB_URL=jdbc:postgresql://<host>:5432/nexuspay
NEXUSPAY_DB_USER / NEXUSPAY_DB_PASSWORD                 # owner role — Flyway only
NEXUSPAY_APP_DB_USER / NEXUSPAY_APP_DB_PASSWORD         # nexuspay_app (RLS-bound app traffic)
NEXUSPAY_SYSTEM_DB_USER / NEXUSPAY_SYSTEM_DB_PASSWORD   # nexuspay_system (BYPASSRLS jobs)
```
`RlsEnforcementConfig` fails fast at startup if any of the app/system credentials are blank.

---

## 3. Cutover procedure

1. **Staging canary.** Deploy to a production-like staging replica with `NEXUSPAY_RLS_ENFORCE=true`
   and real (non-dev) role passwords. Confirm `RlsEnforceIntegrationTest` passes there, then run a
   multi-tenant smoke load: each tenant sees only its own ledger/payments/invoices; cross-tenant jobs
   (outbox relay, analytics rollups, payout/refund reconcilers, balance reconciliation) complete.
2. **Pre-deploy DB checks** (§5 queries): roles exist with correct attributes; every tenant-scoped
   table has a policy; no table is `FORCE`d yet.
3. **Deploy** with the env vars from §2. Flyway runs as owner and re-applies `R__rls_force_owner.sql`
   with `rlsforce=true` (forces all RLS tables). The app starts on the routing datasource.
4. **Post-deploy verification** (first hour, §5): every RLS table is `FORCE`d; a bound app-role
   session sees only its tenant; an unbound session sees **zero** rows (fail-closed); the SYSTEM role
   sees all. Watch logs for `set_config` / connection-auth / `nexuspay_app` errors and for any
   `@SystemTransactional` job failures.

---

## 4. Rollback

1. Set `NEXUSPAY_RLS_ENFORCE=false` (drop the `rls-enforce` profile) and redeploy.
2. Flyway re-applies `R__rls_force_owner.sql` with `rlsforce=false` → `NO FORCE` on all tables.
3. The app reverts to the single owner datasource — byte-identical to pre-cutover. Migrations are
   idempotent; re-running is safe.

> **FORCE/enforce interlock — do not split them.** Both `enforce` and `rlsforce` live in the same
> `rls-enforce` profile and must move together. `enforce=false` + `rlsforce=true` locks the owner out
> of every table (outage). Activate/deactivate the profile as a unit; never override one property
> alone.

---

## 5. Verification queries

```sql
-- Roles present with correct attributes
SELECT rolname, rolcanlogin, rolbypassrls FROM pg_roles
WHERE rolname IN ('nexuspay_app','nexuspay_system');         -- app: bypassrls=false; system: bypassrls=true

-- Every RLS-enabled table is FORCE'd (post-cutover; 0 while dormant)
SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE c.relkind='r' AND c.relrowsecurity AND NOT c.relforcerowsecurity
  AND n.nspname IN ('public','analytics');                   -- expect 0 after cutover

-- Policies missing WITH CHECK (cross-tenant write hole) — expect 0
SELECT schemaname, tablename, policyname FROM pg_policies
WHERE schemaname IN ('public','analytics') AND with_check IS NULL AND cmd IN ('ALL','INSERT','UPDATE');

-- Isolation + fail-closed spot check (run AS nexuspay_app)
SET app.current_tenant_id = 'tenant_a';  SELECT count(*) FROM ledger_accounts;  -- only tenant_a
RESET app.current_tenant_id;             SELECT count(*) FROM ledger_accounts;  -- expect 0
```

---

## 6. Residual risks

- **Header-trust regressions (§1.1).** Re-run the `X-Tenant-Id` grep in CI; a new controller that
  reads the header silently reopens the cross-tenant hole that RLS cannot catch.
- **New tenant-scoped tables.** Any future table with `tenant_id` must ship its RLS policy in the
  same migration, or it leaks once the app runs on the non-owner role. (§1.3 query as a CI gate.)
- **Materialized views.** `REFRESH` requires ownership by `nexuspay_system`; a new MV added without
  the ownership transfer will fail the refresh job under enforcement.
- **Cross-tenant job coverage.** A new `@Scheduled`/Kafka consumer that touches multiple tenants must
  be `@SystemTransactional` (discover under SYSTEM) and re-bind APP+tenant per item — otherwise it
  fails closed (sees zero rows) once enforcement is on.

---

*Sources: `RlsEnforcementConfig`, `RlsRoutingTransactionManager`, `RoleRoutingDataSource`,
`AppTenantTransactionTemplate`, `application-rls-enforce.yml`, migrations `V2001` / `V4020` /
`V3022` / `R__rls_force_owner.sql`, `common.rls.SystemTransactional`; ADR-010/012/013;
`RlsDormancyIntegrationTest` / `RlsEnforceIntegrationTest`.*
