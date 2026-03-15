-- Sprint 3.2: FX rate locks for payment lifecycle
-- A rate lock guarantees the exchange rate from payment intent creation through settlement.

CREATE TABLE fx_rate_locks (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(64) NOT NULL,
    payment_id        VARCHAR(64),
    from_currency     VARCHAR(3) NOT NULL,
    to_currency       VARCHAR(3) NOT NULL,
    rate              DECIMAL(18,8) NOT NULL,
    inverse_rate      DECIMAL(18,8) NOT NULL,
    rate_provider     VARCHAR(50) NOT NULL,
    locked_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ NOT NULL,
    consumed          BOOLEAN NOT NULL DEFAULT false,
    consumed_at       TIMESTAMPTZ,
    CONSTRAINT uk_fx_lock_payment UNIQUE (payment_id)
);

CREATE INDEX idx_fx_locks_expiry ON fx_rate_locks (expires_at) WHERE NOT consumed;
CREATE INDEX idx_fx_locks_tenant ON fx_rate_locks (tenant_id);

-- Row-Level Security
ALTER TABLE fx_rate_locks ENABLE ROW LEVEL SECURITY;

CREATE POLICY fx_rate_locks_tenant_isolation ON fx_rate_locks
    USING (tenant_id = current_setting('app.current_tenant_id', true));

-- Grant access to application role
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'nexuspay_app') THEN
        GRANT SELECT, INSERT, UPDATE ON fx_rate_locks TO nexuspay_app;
    END IF;
END $$;
