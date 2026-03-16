-- Sprint 3.3: PSP fee models for cost-based routing
-- Each PSP can have multiple fee models (per currency, with effective date ranges).

CREATE TABLE psp_fee_models (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             VARCHAR(64) NOT NULL,
    psp_connector         VARCHAR(50) NOT NULL,
    fee_type              VARCHAR(20) NOT NULL,       -- PER_TX, PERCENTAGE, BLENDED, INTERCHANGE_PLUS_PLUS
    per_tx_fee            DECIMAL(10,4),              -- fixed per-transaction fee
    percentage_fee        DECIMAL(8,6),               -- percentage (e.g., 0.029000 = 2.9%)
    interchange_markup_bps INTEGER,                   -- basis points above interchange
    scheme_fee_bps        INTEGER,
    currency              VARCHAR(3) NOT NULL,
    effective_from        DATE NOT NULL,
    effective_to          DATE,
    CONSTRAINT uk_psp_fee UNIQUE (tenant_id, psp_connector, currency, effective_from)
);

CREATE INDEX idx_psp_fee_models_tenant ON psp_fee_models (tenant_id);
CREATE INDEX idx_psp_fee_models_effective ON psp_fee_models (psp_connector, currency, effective_from, effective_to);

-- Row-Level Security
ALTER TABLE psp_fee_models ENABLE ROW LEVEL SECURITY;

CREATE POLICY psp_fee_models_tenant_isolation ON psp_fee_models
    USING (tenant_id = current_setting('app.current_tenant_id', true));

-- Grant access to application role
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'nexuspay_app') THEN
        GRANT SELECT, INSERT, UPDATE ON psp_fee_models TO nexuspay_app;
    END IF;
END $$;

-- Seed default fee models for development
INSERT INTO psp_fee_models (tenant_id, psp_connector, fee_type, per_tx_fee, percentage_fee, currency, effective_from) VALUES
    ('default', 'stripe', 'BLENDED', 0.30, 0.029000, 'USD', '2026-01-01'),
    ('default', 'adyen', 'BLENDED', 0.12, 0.026000, 'USD', '2026-01-01'),
    ('default', 'adyen', 'BLENDED', 0.10, 0.025000, 'EUR', '2026-01-01'),
    ('default', 'dummy_connector', 'PER_TX', 0.00, NULL, 'USD', '2026-01-01');
