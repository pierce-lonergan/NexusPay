-- Marketplace tables for connected accounts, split payments, and payouts
-- Sprint 4.2: Marketplace & Platform Payments

-- Connected sub-merchant accounts
CREATE TABLE connected_accounts (
    id                      VARCHAR(64)    PRIMARY KEY,
    tenant_id               VARCHAR(64)    NOT NULL,
    business_name           VARCHAR(256)   NOT NULL,
    email                   VARCHAR(256)   NOT NULL,
    status                  VARCHAR(16)    NOT NULL DEFAULT 'ONBOARDING',
    kyc_status              VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    country                 VARCHAR(2)     NOT NULL,
    default_currency        VARCHAR(3)     NOT NULL,
    payout_schedule         VARCHAR(16)    NOT NULL DEFAULT 'DAILY',
    payout_minimum          BIGINT         DEFAULT 0,
    platform_fee_percent    NUMERIC(5,2)   DEFAULT 0,
    platform_fee_fixed      BIGINT         DEFAULT 0,
    metadata                JSONB,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_connected_accounts_tenant ON connected_accounts (tenant_id);
CREATE INDEX idx_connected_accounts_status ON connected_accounts (tenant_id, status);
CREATE INDEX idx_connected_accounts_email ON connected_accounts (tenant_id, email);

-- Split payment definitions
CREATE TABLE split_payments (
    id                      VARCHAR(64)    PRIMARY KEY,
    payment_id              VARCHAR(64)    NOT NULL,
    tenant_id               VARCHAR(64)    NOT NULL,
    status                  VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    total_amount            BIGINT         NOT NULL,
    currency                VARCHAR(3)     NOT NULL,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_split_payments_tenant ON split_payments (tenant_id);
CREATE INDEX idx_split_payments_payment ON split_payments (payment_id);

-- Individual split rules per participant
CREATE TABLE split_rules (
    id                      VARCHAR(64)    PRIMARY KEY,
    split_payment_id        VARCHAR(64)    NOT NULL REFERENCES split_payments(id),
    connected_account_id    VARCHAR(64)    NOT NULL REFERENCES connected_accounts(id),
    split_type              VARCHAR(16)    NOT NULL,
    amount                  BIGINT,
    percentage              NUMERIC(5,2),
    calculated_amount       BIGINT,
    currency                VARCHAR(3)     NOT NULL,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_split_rules_payment ON split_rules (split_payment_id);
CREATE INDEX idx_split_rules_account ON split_rules (connected_account_id);

-- Payout disbursements to connected accounts
CREATE TABLE payouts (
    id                      VARCHAR(64)    PRIMARY KEY,
    connected_account_id    VARCHAR(64)    NOT NULL REFERENCES connected_accounts(id),
    tenant_id               VARCHAR(64)    NOT NULL,
    amount                  BIGINT         NOT NULL,
    currency                VARCHAR(3)     NOT NULL,
    status                  VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    method                  VARCHAR(16)    NOT NULL,
    scheduled_at            TIMESTAMPTZ,
    paid_at                 TIMESTAMPTZ,
    failure_reason          VARCHAR(256),
    external_reference      VARCHAR(128),
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_payouts_account ON payouts (connected_account_id);
CREATE INDEX idx_payouts_tenant_status ON payouts (tenant_id, status);
CREATE INDEX idx_payouts_scheduled ON payouts (status, scheduled_at);

-- Platform fee records
CREATE TABLE platform_fees (
    id                      VARCHAR(64)    PRIMARY KEY,
    split_payment_id        VARCHAR(64)    NOT NULL REFERENCES split_payments(id),
    tenant_id               VARCHAR(64)    NOT NULL,
    fee_amount              BIGINT         NOT NULL,
    currency                VARCHAR(3)     NOT NULL,
    fee_percent             NUMERIC(5,2),
    fee_fixed               BIGINT,
    description             VARCHAR(256),
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_platform_fees_split ON platform_fees (split_payment_id);
CREATE INDEX idx_platform_fees_tenant ON platform_fees (tenant_id);

-- Row-Level Security
ALTER TABLE connected_accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_connected_accounts ON connected_accounts
    USING (tenant_id = current_setting('app.current_tenant_id', true));

ALTER TABLE split_payments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_split_payments ON split_payments
    USING (tenant_id = current_setting('app.current_tenant_id', true));

ALTER TABLE split_rules ENABLE ROW LEVEL SECURITY;
-- split_rules has no tenant_id of its own; a row belongs to the tenant of its
-- parent split_payment. Compare the PARENT's tenant to the session tenant.
-- (The original `tenant_id = (subquery)` referenced a non-existent
--  split_rules.tenant_id column -> "column tenant_id does not exist".)
CREATE POLICY tenant_isolation_split_rules ON split_rules
    USING ((SELECT sp.tenant_id FROM split_payments sp WHERE sp.id = split_rules.split_payment_id)
           = current_setting('app.current_tenant_id', true));

ALTER TABLE payouts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_payouts ON payouts
    USING (tenant_id = current_setting('app.current_tenant_id', true));

ALTER TABLE platform_fees ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_platform_fees ON platform_fees
    USING (tenant_id = current_setting('app.current_tenant_id', true));

-- Grant permissions to application role
GRANT SELECT, INSERT, UPDATE, DELETE ON connected_accounts TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON split_payments TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON split_rules TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON payouts TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON platform_fees TO nexuspay_app;
