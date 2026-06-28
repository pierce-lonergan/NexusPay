-- V4040: Mandate / consent resource — the recorded off-session consent of the saved-credential
-- cluster (TEST-3d). Full vertical slice lives in payment-orchestration (io.nexuspay.payment), the
-- SAME module as Customer (V4038) + payment_methods (V4039), so mandate -> cus_/pm_ are in-module
-- opaque-String references (nothing imports a cross-module type; NO payment-orchestration->gateway-api edge).
--
-- NUMBERING: Flyway versions are GLOBAL across all module leaf locations (one shared history;
-- out-of-order DISABLED). V4039 is the current global max, so this is V4040. Lives in the
-- `classpath:db/migration/payment` leaf.
--
-- A mandate records a customer's stored consent to be charged off-session with a saved method. revoke
-- flips status to INACTIVE + stamps revoked_at; it is NOT a soft delete (a revoked mandate stays
-- retrievable), so there is deliberately NO deleted_at column and the by-id finder does NOT filter it out.
CREATE TABLE IF NOT EXISTS mandates (
    id                 VARCHAR(64)  PRIMARY KEY,                 -- mandate_ prefixed
    tenant_id          VARCHAR(64)  NOT NULL,
    customer_id        VARCHAR(64)  NOT NULL,                    -- the cus_ (derived from the pm_'s owner)
    payment_method_id  VARCHAR(64)  NOT NULL,                    -- the pm_ (validated tenant-scoped at create)
    status             VARCHAR(32)  NOT NULL,                    -- PENDING / ACTIVE / INACTIVE
    type               VARCHAR(32)  NOT NULL,                    -- MULTI_USE / SINGLE_USE
    scenario           VARCHAR(64),                              -- e.g. recurring / unscheduled (nullable)
    livemode           BOOLEAN      NOT NULL,                    -- = pm_.livemode = caller key mode
    metadata           JSONB,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    revoked_at         TIMESTAMP WITH TIME ZONE                  -- set on revoke (status -> INACTIVE)
);

-- Tenant-scoped by-id lookups + tenant list are always filtered by tenant_id (SEC-26).
CREATE INDEX IF NOT EXISTS idx_mandates_tenant ON mandates (tenant_id);

-- A customer's mandates are queried by customer_id (always paired with tenant_id in any finder).
CREATE INDEX IF NOT EXISTS idx_mandates_customer ON mandates (customer_id);

-- Tenant isolation (RLS), normalized dialect identical to V4039/V4038 (current_tenant_id() from V2001).
-- DORMANT by default (app connects as owner, RLS bypassed); the rls-enforce profile activates it.
ALTER TABLE mandates ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_mandates ON mandates
    FOR ALL
    USING (tenant_id IS NOT DISTINCT FROM current_tenant_id())
    WITH CHECK (tenant_id IS NOT DISTINCT FROM current_tenant_id());
