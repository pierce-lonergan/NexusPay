-- V4039: Saved payment method — the multi-use credential of the saved-credential cluster (TEST-3b).
-- The FULL vertical slice lives in payment-orchestration (package io.nexuspay.payment), the SAME module
-- as the merged Customer slice (V4038), so pm_ -> cus_ is an in-module reference (customer_id is an
-- opaque cus_ String here; nothing imports a Customer type cross-module, and there is NO
-- payment-orchestration -> gateway-api edge — credential_ref is an opaque string only).
--
-- NUMBERING: Flyway versions are GLOBAL across all module leaf locations (one shared schema history;
-- out-of-order DISABLED). V4038 is the current global max, so this is V4039. This file lives in the
-- `classpath:db/migration/payment` leaf.
--
-- PCI (SEC-BATCH-3): a saved method NEVER stores a raw PAN or any card secret. It stores ONLY display
-- fields (brand/last4/exp/funding) + an OPAQUE credential_ref (the chargeable handle resolved at charge
-- time). There is deliberately NO card-number / cvc column.
--
-- Shape/dialect mirror V4038 (customers): jsonb metadata, TIMESTAMP WITH TIME ZONE, idx_ indexes,
-- IF NOT EXISTS, soft-delete (deleted_at = detach), and the same dormant RLS policy idiom.
CREATE TABLE IF NOT EXISTS payment_methods (
    id             VARCHAR(64)  PRIMARY KEY,                 -- pm_ prefixed
    tenant_id      VARCHAR(64)  NOT NULL,
    customer_id    VARCHAR(64)  NOT NULL,                    -- the cus_ (validated tenant-scoped at attach)
    livemode       BOOLEAN      NOT NULL,                    -- = customer.livemode (enforced at attach)
    type           VARCHAR(32)  NOT NULL,                    -- e.g. 'card'
    brand          VARCHAR(32),
    last4          VARCHAR(4),
    exp_month      INT,
    exp_year       INT,
    funding        VARCHAR(32),
    credential_ref VARCHAR(255) NOT NULL,                    -- OPAQUE chargeable handle (no PAN)
    metadata       JSONB,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMP WITH TIME ZONE                  -- soft delete = DETACH (NULL = attached)
);

-- Tenant-scoped by-id lookups are always filtered by tenant_id (SEC-26).
CREATE INDEX IF NOT EXISTS idx_payment_methods_tenant ON payment_methods (tenant_id);

-- A customer's saved methods are listed by customer_id (always paired with tenant_id in the finder).
CREATE INDEX IF NOT EXISTS idx_payment_methods_customer ON payment_methods (customer_id);

-- Tenant isolation (RLS), normalized dialect identical to V4038/V4030 (current_tenant_id() from V2001).
-- DORMANT by default (app connects as owner, RLS bypassed); the rls-enforce profile activates it.
ALTER TABLE payment_methods ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_payment_methods ON payment_methods
    FOR ALL
    USING (tenant_id IS NOT DISTINCT FROM current_tenant_id())
    WITH CHECK (tenant_id IS NOT DISTINCT FROM current_tenant_id());
