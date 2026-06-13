-- B-024 / B-027: capture-hold for fraud-REVIEW payments.
-- A fraud REVIEW (or a downgraded server-rail BLOCK) authorizes the payment but must
-- NOT auto-capture. This row is (a) the enforceable hold — capture is refused while
-- HELD — and (b) the durable link from the gateway payment id to its fraud assessment
-- (the linkage the pre-auth gate previously lacked). An authorized back-office action
-- flips HELD -> RELEASED to allow capture.
CREATE TABLE IF NOT EXISTS payment_capture_hold (
    payment_id          VARCHAR(64)  PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    fraud_assessment_id VARCHAR(64),
    status              VARCHAR(16)  NOT NULL DEFAULT 'HELD',
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    released_by         VARCHAR(128),
    released_at         TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_capture_hold_status CHECK (status IN ('HELD', 'RELEASED'))
);

CREATE INDEX IF NOT EXISTS idx_payment_capture_hold_tenant ON payment_capture_hold (tenant_id);

-- Tenant isolation (RLS), matching the normalized dialect (current_tenant_id() helper
-- from V2001). USING + WITH CHECK so a tenant can neither read nor write another's holds.
ALTER TABLE payment_capture_hold ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_capture_hold ON payment_capture_hold
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
