-- Fraud rules table with condition DSL, A/B testing support, and RLS
-- Sprint 3.1: Fraud Prevention

CREATE TABLE fraud_rules (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36)  NOT NULL,
    rule_name         VARCHAR(255) NOT NULL,
    rule_type         VARCHAR(50)  NOT NULL,     -- VELOCITY, AMOUNT_THRESHOLD, GEO_RESTRICTION, BIN_CHECK, DEVICE_FINGERPRINT
    condition_dsl     JSONB        NOT NULL,     -- rule condition stored as JSON DSL
    action            VARCHAR(20)  NOT NULL,     -- BLOCK, REVIEW, SCORE_ADJUST
    score_adjustment  INTEGER      DEFAULT 0,
    priority          INTEGER      NOT NULL DEFAULT 100,
    version           INTEGER      NOT NULL DEFAULT 1,
    ab_test_group     VARCHAR(20),               -- NULL = always active, 'A' or 'B' for A/B testing
    ab_test_traffic   DECIMAL(5,4),              -- percentage of traffic for this group (0.0000-1.0000)
    enabled           BOOLEAN      NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        VARCHAR(255) NOT NULL,
    CONSTRAINT uk_fraud_rules_tenant_name_version UNIQUE (tenant_id, rule_name, version)
);

CREATE INDEX idx_fraud_rules_tenant_enabled ON fraud_rules (tenant_id, enabled) WHERE enabled = true;
CREATE INDEX idx_fraud_rules_type ON fraud_rules (rule_type);

-- Row-Level Security
ALTER TABLE fraud_rules ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_fraud_rules ON fraud_rules
    USING (tenant_id = current_setting('app.current_tenant_id', true));
