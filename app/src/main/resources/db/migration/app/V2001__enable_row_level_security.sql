-- ============================================================================
-- V2001: Enable PostgreSQL Row-Level Security (RLS) on all tenant-scoped tables
--
-- Sprint 2.1 — Multi-Tenancy & Security
--
-- This migration:
--   1. Creates a helper function current_tenant_id() that reads the
--      session variable 'app.current_tenant_id' set by the application
--      via SET LOCAL on each transaction.
--   2. Adds tenant_id to the 'postings' table (previously inherited via
--      journal_entry_id FK but needs direct column for RLS).
--   3. Creates a dedicated application role 'nexuspay_app' with limited
--      privileges (no superuser bypass of RLS).
--   4. Enables RLS on all tenant-scoped tables with USING and WITH CHECK
--      policies for the application role.
--
-- IMPORTANT: The application MUST execute SET LOCAL app.current_tenant_id = ?
-- at the beginning of each transaction. Without this, the current_tenant_id()
-- function returns NULL and RLS policies block all rows.
--
-- The superuser (nexuspay) bypasses RLS for migrations and system operations.
-- The application role (nexuspay_app) is subject to RLS.
-- ============================================================================

-- 1. Helper function: reads tenant ID from session variable
CREATE OR REPLACE FUNCTION current_tenant_id() RETURNS VARCHAR AS $$
BEGIN
    RETURN current_setting('app.current_tenant_id', true);
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION current_tenant_id() IS
    'Returns the current tenant ID from the session variable app.current_tenant_id. '
    'Returns NULL if not set (which causes RLS to block all rows for non-superuser roles).';

-- 2. Add tenant_id to postings table (was inherited via journal_entry FK)
ALTER TABLE postings ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);

-- Backfill tenant_id from parent journal_entries
UPDATE postings p
SET tenant_id = je.tenant_id
FROM journal_entries je
WHERE p.journal_entry_id = je.id
  AND p.tenant_id IS NULL;

-- Set default and NOT NULL after backfill
ALTER TABLE postings ALTER COLUMN tenant_id SET DEFAULT 'default';
ALTER TABLE postings ALTER COLUMN tenant_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_postings_tenant ON postings(tenant_id);

-- 3. Create application role (if not exists) with restricted privileges
-- This role is subject to RLS; the superuser role bypasses it.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'nexuspay_app') THEN
        CREATE ROLE nexuspay_app LOGIN PASSWORD 'nexuspay_app_local';
    END IF;
END
$$;

GRANT CONNECT ON DATABASE nexuspay TO nexuspay_app;
GRANT USAGE ON SCHEMA public TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO nexuspay_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO nexuspay_app;

-- Ensure future tables also grant to nexuspay_app
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nexuspay_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO nexuspay_app;

-- 4. Enable RLS on all tenant-scoped tables
-- Pattern: ENABLE RLS → SELECT/UPDATE/DELETE policy (USING) → INSERT policy (WITH CHECK)

-- --- ledger_accounts ---
ALTER TABLE ledger_accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_ledger_accounts ON ledger_accounts
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- --- journal_entries ---
ALTER TABLE journal_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_journal_entries ON journal_entries
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- --- postings ---
ALTER TABLE postings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_postings ON postings
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- --- inbound_webhooks ---
ALTER TABLE inbound_webhooks ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_inbound_webhooks ON inbound_webhooks
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- --- event_outbox ---
ALTER TABLE event_outbox ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_event_outbox ON event_outbox
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- --- api_keys ---
ALTER TABLE api_keys ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_api_keys ON api_keys
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- --- audit_log ---
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_audit_log ON audit_log
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- --- webhook_endpoints ---
ALTER TABLE webhook_endpoints ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_webhook_endpoints ON webhook_endpoints
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- --- pending_approvals ---
ALTER TABLE pending_approvals ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_pending_approvals ON pending_approvals
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- 5. FORCE RLS for table owners too (except superuser)
-- This ensures even the table owner (nexuspay) is subject to RLS when
-- connected as nexuspay_app. The superuser still bypasses.
-- NOTE: We do NOT use FORCE ROW LEVEL SECURITY here because the migration
-- user (superuser/nexuspay) needs to operate without RLS for migrations
-- and system jobs. Instead, we rely on the nexuspay_app role being non-superuser.
