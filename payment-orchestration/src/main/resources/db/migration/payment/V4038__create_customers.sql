-- V4038: Customer resource — the anchor of the saved-credential cluster (TEST-3a).
-- The FULL vertical slice lives in payment-orchestration (package io.nexuspay.payment), mirroring how
-- the dispute slice hosts its own controller+service+entity+repo. billing references a customerId as an
-- opaque String only; nothing imports a Customer type cross-module.
--
-- NUMBERING: Flyway versions are GLOBAL across all module leaf locations (one shared schema history;
-- out-of-order DISABLED). V4037 is the current global max, so this is V4038. This file lives in the
-- `classpath:db/migration/payment` leaf (registered in app/src/main/resources/application.yml).
--
-- Shape/dialect mirror V4030 (payment_webhook_metadata): jsonb metadata, TIMESTAMP WITH TIME ZONE,
-- idx_ indexes, IF NOT EXISTS, and the same RLS policy idiom (dormant by default; enforced only under
-- the rls-enforce profile via current_tenant_id()).
CREATE TABLE IF NOT EXISTS customers (
    id            VARCHAR(64)  PRIMARY KEY,                 -- cus_ prefixed
    tenant_id     VARCHAR(64)  NOT NULL,
    livemode      BOOLEAN      NOT NULL,
    email         VARCHAR(320),
    name          VARCHAR(256),
    description   VARCHAR(1024),
    metadata      JSONB,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMP WITH TIME ZONE                  -- soft delete (NULL = live)
);

-- Tenant-scoped enumeration / by-id lookups are always filtered by tenant_id (SEC-26).
CREATE INDEX IF NOT EXISTS idx_customers_tenant ON customers (tenant_id);

-- Tenant isolation (RLS), normalized dialect identical to V4030/V4022 (current_tenant_id() from V2001).
-- DORMANT by default (app connects as owner, RLS bypassed); the rls-enforce profile activates it.
ALTER TABLE customers ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_customers ON customers
    FOR ALL
    USING (tenant_id IS NOT DISTINCT FROM current_tenant_id())
    WITH CHECK (tenant_id IS NOT DISTINCT FROM current_tenant_id());
