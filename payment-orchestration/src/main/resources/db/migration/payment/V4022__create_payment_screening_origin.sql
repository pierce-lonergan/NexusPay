-- B-029: server-owned originating screening context for a created payment.
-- At CREATE time the gate resolves the screening mode + tenant from a TRUSTED call-site
-- identity (CallContext), never from client-shaped request metadata. confirmPayment must
-- re-screen with the SAME trusted (tenant, mode) — but the intent's free-form metadata blob
-- is not a trustworthy carrier for authority. This row is the durable, server-owned record
-- of the originating (tenant, mode), keyed by the gateway payment id, so confirm reads its
-- authority from here instead of re-deriving it from the (re-classifiable) metadata blob.
--
-- NOTE on numbering: all module migration locations share ONE Flyway schema history
-- (app/.../application.yml lists every leaf with a single default history table), so versions
-- are GLOBAL and must monotonically exceed the current max (V4021) — hence V4022, not a
-- per-module V40xx. Out-of-order is disabled.
CREATE TABLE IF NOT EXISTS payment_screening_origin (
    gateway_payment_id  VARCHAR(64)  PRIMARY KEY,
    tenant_id           VARCHAR(64),
    screening_mode      VARCHAR(20)  NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_screening_mode CHECK (screening_mode IN ('INTERACTIVE', 'SERVER_RECURRING', 'SERVER_OTHER'))
);

CREATE INDEX IF NOT EXISTS idx_payment_screening_origin_tenant ON payment_screening_origin (tenant_id);

-- Tenant isolation (RLS), matching the normalized dialect (current_tenant_id() helper from
-- V2001). tenant_id is nullable (a workflow ingress may carry no tenant); a NULL-tenant row is
-- written/read only when no tenant is set on the SecurityContext, so the policy permits NULL via
-- IS NOT DISTINCT FROM (NULL matches NULL).
ALTER TABLE payment_screening_origin ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_screening_origin ON payment_screening_origin
    FOR ALL
    USING (tenant_id IS NOT DISTINCT FROM current_tenant_id())
    WITH CHECK (tenant_id IS NOT DISTINCT FROM current_tenant_id());
