-- Fraud event audit trail for all fraud check outcomes and rule triggers
-- Sprint 3.1: Fraud Prevention

CREATE TABLE fraud_events (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36)  NOT NULL,
    event_type        VARCHAR(50)  NOT NULL,      -- FRAUD_CHECK_PASSED, FRAUD_CHECK_FAILED, FRAUD_CHECK_REVIEW, RULE_TRIGGERED
    assessment_id     UUID         NOT NULL,
    payment_id        VARCHAR(64)  NOT NULL,
    rule_id           UUID,
    payload           JSONB        NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_fraud_events_assessment ON fraud_events (assessment_id);
CREATE INDEX idx_fraud_events_type_time ON fraud_events (tenant_id, event_type, created_at);

-- Row-Level Security
ALTER TABLE fraud_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_fraud_events ON fraud_events
    USING (tenant_id = current_setting('app.current_tenant_id', true));
