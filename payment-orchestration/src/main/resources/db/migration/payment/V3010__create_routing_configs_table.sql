-- Sprint 3.3: Tenant routing configurations
-- Each tenant has one or more routing configs; exactly one is active (enabled).

CREATE TABLE routing_configs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(64) NOT NULL,
    config_name       VARCHAR(100) NOT NULL,
    strategy          VARCHAR(30) NOT NULL,        -- COST_BASED, SUCCESS_RATE, LATENCY, ROUND_ROBIN, WEIGHTED, FAILOVER
    psp_list          JSONB NOT NULL DEFAULT '[]',  -- ordered list of PSPs with weights
    cascade_enabled   BOOLEAN NOT NULL DEFAULT true,
    max_cascade_depth INTEGER NOT NULL DEFAULT 3,
    filters           JSONB NOT NULL DEFAULT '{}',  -- additional filters (min amount, currency, etc.)
    ab_test_id        UUID,                         -- NULL if not in A/B test
    ab_test_traffic   DOUBLE PRECISION,             -- fraction 0.0-1.0; matches RoutingConfigEntity.abTestTraffic (Double)
    enabled           BOOLEAN NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_routing_config UNIQUE (tenant_id, config_name)
);

CREATE INDEX idx_routing_configs_tenant ON routing_configs (tenant_id, enabled);

-- Row-Level Security
ALTER TABLE routing_configs ENABLE ROW LEVEL SECURITY;

CREATE POLICY routing_configs_tenant_isolation ON routing_configs
    USING (tenant_id = current_setting('app.current_tenant_id', true));

-- Grant access to application role
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'nexuspay_app') THEN
        GRANT SELECT, INSERT, UPDATE ON routing_configs TO nexuspay_app;
    END IF;
END $$;

-- Seed default routing config
INSERT INTO routing_configs (tenant_id, config_name, strategy, psp_list, cascade_enabled, max_cascade_depth) VALUES
    ('default', 'default', 'SUCCESS_RATE', '["stripe", "adyen", "dummy_connector"]', true, 3);
