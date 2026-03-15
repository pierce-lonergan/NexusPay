-- Sprint 3.2: Merchant currency preferences
-- Each merchant configures their preferred settlement currency, FX markup, and rate provider.

CREATE TABLE merchant_currency_prefs (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                   VARCHAR(64) NOT NULL,
    settlement_currency         VARCHAR(3) NOT NULL DEFAULT 'USD',
    auto_convert                BOOLEAN NOT NULL DEFAULT true,
    fx_markup_bps               INTEGER NOT NULL DEFAULT 0,
    rate_provider               VARCHAR(50) NOT NULL DEFAULT 'ECB',
    rate_lock_duration_minutes  INTEGER NOT NULL DEFAULT 15,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_merchant_ccy_pref UNIQUE (tenant_id)
);

-- Row-Level Security
ALTER TABLE merchant_currency_prefs ENABLE ROW LEVEL SECURITY;

CREATE POLICY merchant_currency_prefs_tenant_isolation ON merchant_currency_prefs
    USING (tenant_id = current_setting('app.current_tenant_id', true));

-- Grant access to application role
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'nexuspay_app') THEN
        GRANT SELECT, INSERT, UPDATE ON merchant_currency_prefs TO nexuspay_app;
    END IF;
END $$;

-- Seed default preferences for development
INSERT INTO merchant_currency_prefs (id, tenant_id, settlement_currency, auto_convert, fx_markup_bps, rate_provider, rate_lock_duration_minutes)
VALUES (gen_random_uuid(), 'default', 'USD', true, 0, 'ECB', 15)
ON CONFLICT (tenant_id) DO NOTHING;
