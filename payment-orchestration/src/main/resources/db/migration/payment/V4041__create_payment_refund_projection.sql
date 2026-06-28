-- V4041: Payments / Refunds READ-MODEL projection (GAP-076 / critique v3 F1).
--
-- A DURABLE, queryable denormalization of payment + refund state so an integrator can
--   GET /v1/payments?status=...&customer_id=...   and   GET /v1/refunds?payment=...
-- Today only POST + GET-by-id exist; there is no payments/refunds table — live state lives in
-- HyperSwitch and TEST state lived in the in-process MockPaymentGatewayPort map (ephemeral).
--
-- ★ CARDINAL RULE: this is a BEST-EFFORT, READ-ONLY projection, NOT a source of truth. A projection
-- write is best-effort (swallowed on failure) and can never fail/block/rollback a payment/refund op.
-- It is NEVER read to move money, re-drive a capture, or reconcile the ledger; it is served ONLY by the
-- new GET list endpoints. The double-entry ledger + the PSP/mock remain the sole sources of truth.
--
-- NUMBERING: Flyway versions are GLOBAL across all module leaf locations (one shared schema history;
-- out-of-order DISABLED). V4040 is the current global max, so this is V4041. Lives in the
-- `classpath:db/migration/payment` leaf.
--
-- Shape/dialect mirror V4038/V4039/V4040: VARCHAR(64) ids, TIMESTAMP WITH TIME ZONE, IF NOT EXISTS,
-- idx_ indexes, and the dormant tenant_isolation RLS idiom (current_tenant_id() from V2001; activated
-- only under the rls-enforce profile, app-layer scoping is the primary control).
--
-- These are FRESH projection tables, NOT a repurpose of V4030/V4022/V4010 (those are satellite tables,
-- never read-models). No raw PAN / card data / secret column ever lives here (PCI). No soft-delete column
-- (a projection row is never deleted; it forward-fills).
--
-- TABLE NAMES: `payments` / `refunds` are unused today (no such table exists — live state is in
-- HyperSwitch, test state was in the in-process mock map). If CI flags a name collision, prefix to
-- `payments_projection` / `refunds_projection` (the entities' @Table name is the only other edit).

CREATE TABLE IF NOT EXISTS payments (
    payment_id      VARCHAR(64)  PRIMARY KEY,                 -- = PaymentResponse.gatewayPaymentId (idempotent upsert key)
    tenant_id       VARCHAR(64)  NOT NULL,
    livemode        BOOLEAN      NOT NULL,
    status          VARCHAR(32)  NOT NULL,                    -- requires_payment_method/_confirmation/_capture/requires_action/processing/succeeded/failed/cancelled
    amount          BIGINT       NOT NULL,                    -- minor units
    currency        VARCHAR(8),
    capture_method  VARCHAR(16),
    customer_id     VARCHAR(64),
    connector_name  VARCHAR(64),
    error_code      VARCHAR(128),
    error_message   VARCHAR(512),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,        -- from PaymentResponse.createdAt (NOT now())
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Tenant-scoped enumeration (newest first) is the primary access path for GET /v1/payments.
CREATE INDEX IF NOT EXISTS idx_payments_tenant_created  ON payments (tenant_id, created_at DESC);
-- status filter (tenant_id, status) and customer filter (tenant_id, customer_id).
CREATE INDEX IF NOT EXISTS idx_payments_tenant_status   ON payments (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_payments_tenant_customer ON payments (tenant_id, customer_id);

-- Tenant isolation (RLS), normalized dialect identical to V4039/V4040 (current_tenant_id() from V2001).
-- DORMANT by default (app connects as owner, RLS bypassed); the rls-enforce profile activates it.
ALTER TABLE payments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_payments ON payments
    FOR ALL
    USING (tenant_id IS NOT DISTINCT FROM current_tenant_id())
    WITH CHECK (tenant_id IS NOT DISTINCT FROM current_tenant_id());

CREATE TABLE IF NOT EXISTS refunds (
    refund_id       VARCHAR(64)  PRIMARY KEY,                 -- = RefundResponse.gatewayRefundId
    payment_id      VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    livemode        BOOLEAN      NOT NULL,
    status          VARCHAR(32)  NOT NULL,                    -- pending/succeeded/failed
    amount          BIGINT       NOT NULL,
    currency        VARCHAR(8),
    reason          VARCHAR(255),
    connector_name  VARCHAR(64),
    error_code      VARCHAR(128),
    error_message   VARCHAR(512),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Tenant-scoped enumeration (newest first) backs GET /v1/refunds; payment filter backs ?payment=.
CREATE INDEX IF NOT EXISTS idx_refunds_tenant_created  ON refunds (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_refunds_payment         ON refunds (payment_id);
CREATE INDEX IF NOT EXISTS idx_refunds_tenant_status   ON refunds (tenant_id, status);

ALTER TABLE refunds ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_refunds ON refunds
    FOR ALL
    USING (tenant_id IS NOT DISTINCT FROM current_tenant_id())
    WITH CHECK (tenant_id IS NOT DISTINCT FROM current_tenant_id());
