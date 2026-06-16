-- INT-1: server-owned merchant correlation metadata for outbound webhook enrichment.
-- Persisted at payment-create time (the GatedPaymentGateway.doCreate chokepoint) keyed by the
-- gateway_payment_id, scoped to the server-derived TRUSTED tenant. Read at delivery and placed in the
-- canonical webhook envelope's data.metadata. ONLY the merchant 'metadata' correlation map is stored
-- (NEVER payment_method_data / PAN / card data — the write-side sanitize() strips those, plus the
-- source/workflow/tenant_id authority markers). Size + key-count are capped at write time.
-- No backfill is needed: a missing row yields data.metadata = {}, never a delivery failure.
--
-- Mirrors payment_screening_origin (V4022) in shape and RLS dialect.
--
-- NUMBERING: Flyway versions are GLOBAL across module leaf locations (see V4022's header: all module
-- migration locations share ONE Flyway schema history). V4029 is the current global max, so this is
-- V4030. Out-of-order is disabled.
CREATE TABLE IF NOT EXISTS payment_webhook_metadata (
    gateway_payment_id  VARCHAR(64)  PRIMARY KEY,
    tenant_id           VARCHAR(64),
    metadata_json       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_payment_webhook_metadata_tenant
    ON payment_webhook_metadata (tenant_id);

-- Tenant isolation (RLS), normalized dialect identical to V4022 (current_tenant_id() helper from
-- V2001). tenant_id is nullable; a NULL-tenant row (a workflow ingress with no tenant) is written/read
-- only when no tenant is set on the SecurityContext, so the policy permits NULL via IS NOT DISTINCT
-- FROM (NULL matches NULL).
ALTER TABLE payment_webhook_metadata ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_webhook_metadata ON payment_webhook_metadata
    FOR ALL
    USING (tenant_id IS NOT DISTINCT FROM current_tenant_id())
    WITH CHECK (tenant_id IS NOT DISTINCT FROM current_tenant_id());
