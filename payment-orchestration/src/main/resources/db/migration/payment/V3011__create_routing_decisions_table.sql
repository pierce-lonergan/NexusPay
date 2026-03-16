-- Sprint 3.3: Routing decisions audit trail
-- Every routing decision is recorded for auditability and A/B test analysis.

CREATE TABLE routing_decisions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           VARCHAR(64) NOT NULL,
    payment_id          VARCHAR(64) NOT NULL,
    strategy_used       VARCHAR(30) NOT NULL,
    config_id           UUID NOT NULL REFERENCES routing_configs(id),
    selected_psp        VARCHAR(50) NOT NULL,
    candidate_scores    JSONB NOT NULL,              -- all PSP scores for auditability
    cascade_depth       INTEGER NOT NULL DEFAULT 0,
    cascade_psps        JSONB,                       -- list of PSPs attempted in cascade
    final_psp           VARCHAR(50),                 -- PSP that ultimately processed (after cascade)
    ab_test_id          UUID,
    ab_test_group       VARCHAR(1),                  -- A or B
    decided_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    decision_latency_ms INTEGER NOT NULL
);

CREATE INDEX idx_routing_decisions_payment ON routing_decisions (payment_id);
CREATE INDEX idx_routing_decisions_tenant ON routing_decisions (tenant_id, decided_at);
CREATE INDEX idx_routing_decisions_ab ON routing_decisions (ab_test_id, ab_test_group)
    WHERE ab_test_id IS NOT NULL;

-- Row-Level Security
ALTER TABLE routing_decisions ENABLE ROW LEVEL SECURITY;

CREATE POLICY routing_decisions_tenant_isolation ON routing_decisions
    USING (tenant_id = current_setting('app.current_tenant_id', true));

-- Grant access to application role
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'nexuspay_app') THEN
        GRANT SELECT, INSERT ON routing_decisions TO nexuspay_app;
    END IF;
END $$;
