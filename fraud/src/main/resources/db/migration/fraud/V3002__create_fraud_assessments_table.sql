-- Fraud assessment results with scoring, decision, and review status
-- Sprint 3.1: Fraud Prevention

CREATE TABLE fraud_assessments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36)  NOT NULL,
    payment_id        VARCHAR(64)  NOT NULL,
    native_score      INTEGER      NOT NULL,      -- 0-100 from native rules
    frm_score         INTEGER,                    -- 0-100 from external FRM (nullable if unavailable)
    frm_provider      VARCHAR(50),                -- SIFT, SIGNIFYD, NATIVE_ONLY
    aggregated_score  INTEGER      NOT NULL,      -- weighted combination
    decision          VARCHAR(10)  NOT NULL,      -- ALLOW, REVIEW, BLOCK
    triggered_rules   JSONB        NOT NULL DEFAULT '[]',
    risk_signals      JSONB        NOT NULL DEFAULT '{}',
    review_status     VARCHAR(20),                -- NULL, PENDING_REVIEW, APPROVED, REJECTED
    reviewed_by       VARCHAR(255),
    reviewed_at       TIMESTAMPTZ,
    assessed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    latency_ms        INTEGER      NOT NULL
);

CREATE INDEX idx_fraud_assessments_payment ON fraud_assessments (payment_id);
CREATE INDEX idx_fraud_assessments_decision ON fraud_assessments (tenant_id, decision);
CREATE INDEX idx_fraud_assessments_review ON fraud_assessments (tenant_id, review_status)
    WHERE review_status IS NOT NULL;

-- Row-Level Security
ALTER TABLE fraud_assessments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_fraud_assessments ON fraud_assessments
    USING (tenant_id = current_setting('app.current_tenant_id', true));
