-- ============================================================================
-- B-002 migration set 1 (DORMANT — additive, owner-run, idempotent).
-- Prepares RLS for runtime activation WITHOUT changing current behavior (the app
-- still connects as the owner, which bypasses RLS until the human cutover):
--   1. nexuspay_system role (BYPASSRLS) for cross-tenant background jobs (Option B —
--      physical role isolation, NO in-policy 'system' escape hatch).
--   2. cross-schema grants for nexuspay_app + nexuspay_system over public + analytics
--      (V2001 granted public to nexuspay_app only — closes the permission gap that
--      would deny analytics the instant traffic leaves the owner).
--   3. materialized-view ownership so the refresh job (on the system role) can REFRESH.
--   4. SECURITY FIX: close the write-side cross-tenant leak. Many RLS policies have a
--      USING clause but NO WITH CHECK, so a mislabeled INSERT/UPDATE can write a row
--      for ANOTHER tenant. Copy each such policy's existing USING expression verbatim
--      into WITH CHECK (no expression rewrite → child-table parent-subquery policies
--      stay correct). This hardens writes even before the owner→nexuspay_app cutover.
-- ============================================================================

-- 1. System role (BYPASSRLS). Dev/local password; prod sets it out-of-band (ALTER ROLE).
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'nexuspay_system') THEN
        CREATE ROLE nexuspay_system LOGIN BYPASSRLS NOSUPERUSER NOCREATEDB NOCREATEROLE
            PASSWORD 'nexuspay_system_local';
    END IF;
END
$$;

GRANT CONNECT ON DATABASE nexuspay TO nexuspay_system;

-- 2. Cross-schema grants (public + analytics are the only schemas holding RLS tables).
DO $$
DECLARE sch TEXT;
BEGIN
    FOREACH sch IN ARRAY ARRAY['public', 'analytics'] LOOP
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO nexuspay_app, nexuspay_system', sch);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO nexuspay_app, nexuspay_system', sch);
        EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO nexuspay_app, nexuspay_system', sch);
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nexuspay_app, nexuspay_system', sch);
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO nexuspay_app, nexuspay_system', sch);
    END LOOP;
END
$$;

-- 3. Materialized-view ownership for the refresh job (system role REFRESHes; nexuspay_app cannot).
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_matviews WHERE schemaname = 'analytics' AND matviewname = 'mv_auth_rate_daily_refresh') THEN
        ALTER MATERIALIZED VIEW analytics.mv_auth_rate_daily_refresh OWNER TO nexuspay_system;
    END IF;
    IF EXISTS (SELECT FROM pg_matviews WHERE schemaname = 'analytics' AND matviewname = 'mv_psp_health_trend') THEN
        ALTER MATERIALIZED VIEW analytics.mv_psp_health_trend OWNER TO nexuspay_system;
    END IF;
END
$$;

-- 4. Close the write-side cross-tenant leak: add WITH CHECK = (existing USING) to every
--    tenant policy that lacks one. Verbatim copy of each policy's own qual, so the
--    simple-column policies AND the child-table parent-subquery policies (split_rules,
--    workflow_versions, webhook_triggers) all get a correct, matching write check.
--    Idempotent: policies that already have a WITH CHECK are skipped on re-run.
DO $$
DECLARE p RECORD;
BEGIN
    FOR p IN
        SELECT schemaname, tablename, policyname, qual
        FROM pg_policies
        WHERE qual IS NOT NULL
          AND with_check IS NULL
          AND schemaname IN ('public', 'analytics')
          -- WITH CHECK only applies to write commands; skip any SELECT/DELETE-only policy.
          AND cmd IN ('ALL', 'INSERT', 'UPDATE')
    LOOP
        EXECUTE format('ALTER POLICY %I ON %I.%I USING (%s) WITH CHECK (%s)',
                       p.policyname, p.schemaname, p.tablename, p.qual, p.qual);
    END LOOP;
END
$$;
