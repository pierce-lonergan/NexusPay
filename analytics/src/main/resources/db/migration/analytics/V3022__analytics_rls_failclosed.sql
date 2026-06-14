-- ============================================================================
-- B-002 review finding #3 — make the analytics RLS policies FAIL-CLOSED to zero rows
-- (not ERROR) for an unbound app transaction.
--
-- V3017-V3020 wrote each analytics policy as USING (tenant_id = current_setting(
-- 'app.current_tenant_id')) — the BARE form, with no missing_ok argument. When the GUC is
-- unset (the fail-closed path: an APP-role transaction with no tenant bound under RLS
-- enforcement), bare current_setting RAISES 'unrecognized configuration parameter
-- app.current_tenant_id' and ABORTS the transaction, instead of returning NULL → zero rows.
-- Every other module's policies use the current_tenant_id() helper (V2001), which is
-- current_setting('app.current_tenant_id', true) — missing_ok → NULL when unset.
--
-- This normalizes the 7 analytics policies onto that same helper so an unbound APP txn
-- deterministically sees zero rows with no error (matching public/gateway/payment). Owner-run,
-- idempotent (ALTER POLICY re-applies the same expression). Sets WITH CHECK too so the later
-- V4020 write-leak DO-loop (with_check IS NULL filter) skips these as already-correct.
-- SYSTEM (BYPASSRLS) jobs and the dormant owner connection are unaffected (they bypass policies).
-- ============================================================================
DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'auth_rate_hourly', 'auth_rate_daily', 'auth_rate_monthly',
        'psp_health_snapshots', 'revenue_hourly', 'revenue_daily', 'decline_daily'
    ] LOOP
        IF EXISTS (
            SELECT 1 FROM pg_policies
            WHERE schemaname = 'analytics' AND tablename = t AND policyname = 'tenant_isolation'
        ) THEN
            EXECUTE format(
                'ALTER POLICY tenant_isolation ON analytics.%I '
                || 'USING (tenant_id = current_tenant_id()) '
                || 'WITH CHECK (tenant_id = current_tenant_id())', t);
        END IF;
    END LOOP;
END
$$;
