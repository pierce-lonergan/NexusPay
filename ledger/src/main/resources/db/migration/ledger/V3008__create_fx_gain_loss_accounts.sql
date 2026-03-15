-- Sprint 3.2: FX gain/loss tracking accounts
-- Tracks realized and unrealized FX gains/losses per currency pair per tenant.

CREATE TABLE fx_gain_loss_accounts (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             VARCHAR(64) NOT NULL,
    currency_pair         VARCHAR(7) NOT NULL,
    account_id            VARCHAR(64) NOT NULL REFERENCES ledger_accounts(id),
    realized_gain_loss    DECIMAL(18,4) NOT NULL DEFAULT 0,
    unrealized_gain_loss  DECIMAL(18,4) NOT NULL DEFAULT 0,
    last_calculated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_fx_gl_tenant_pair UNIQUE (tenant_id, currency_pair)
);

CREATE INDEX idx_fx_gl_tenant ON fx_gain_loss_accounts (tenant_id);
CREATE INDEX idx_fx_gl_account ON fx_gain_loss_accounts (account_id);

-- Row-Level Security
ALTER TABLE fx_gain_loss_accounts ENABLE ROW LEVEL SECURITY;

CREATE POLICY fx_gain_loss_accounts_tenant_isolation ON fx_gain_loss_accounts
    USING (tenant_id = current_setting('app.current_tenant_id', true));

-- Grant access to application role
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'nexuspay_app') THEN
        GRANT SELECT, INSERT, UPDATE ON fx_gain_loss_accounts TO nexuspay_app;
    END IF;
END $$;
