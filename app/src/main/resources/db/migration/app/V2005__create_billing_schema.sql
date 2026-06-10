-- =============================================================================
-- V2005: Subscription Billing Schema
-- Sprint 2.5a — Product catalog, subscriptions, invoices, dunning
-- =============================================================================

-- ---------------------------------------------------------------------------
-- products — what is being sold
-- ---------------------------------------------------------------------------
CREATE TABLE products (
    id          VARCHAR(64) PRIMARY KEY,              -- prod_xxx
    tenant_id   VARCHAR(64) NOT NULL,
    name        VARCHAR(256) NOT NULL,
    description TEXT,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    metadata    JSONB,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_products_tenant ON products(tenant_id);

-- ---------------------------------------------------------------------------
-- prices — how much (flat, tiered, volume, per-unit, package)
-- ---------------------------------------------------------------------------
CREATE TABLE prices (
    id                      VARCHAR(64) PRIMARY KEY,  -- price_xxx
    product_id              VARCHAR(64) NOT NULL REFERENCES products(id),
    tenant_id               VARCHAR(64) NOT NULL,
    currency                VARCHAR(3) NOT NULL,      -- ISO 4217
    pricing_model           VARCHAR(16) NOT NULL,     -- FLAT, PER_UNIT, TIERED, VOLUME, PACKAGE
    unit_amount             BIGINT,                   -- minor units (for FLAT/PER_UNIT)
    tiers                   JSONB,                    -- [{up_to, unit_amount, flat_amount}]
    billing_interval        VARCHAR(16) NOT NULL,     -- MONTH, YEAR, WEEK, DAY
    billing_interval_count  INTEGER NOT NULL DEFAULT 1,
    trial_days              INTEGER DEFAULT 0,
    active                  BOOLEAN NOT NULL DEFAULT TRUE,
    effective_from          TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at              TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_prices_product ON prices(product_id);

-- ---------------------------------------------------------------------------
-- subscriptions — recurring billing lifecycle
-- ---------------------------------------------------------------------------
CREATE TABLE subscriptions (
    id                      VARCHAR(64) PRIMARY KEY,  -- sub_xxx
    tenant_id               VARCHAR(64) NOT NULL,
    customer_id             VARCHAR(64) NOT NULL,
    price_id                VARCHAR(64) NOT NULL REFERENCES prices(id),
    status                  VARCHAR(16) NOT NULL DEFAULT 'TRIALING',
    quantity                INTEGER NOT NULL DEFAULT 1,
    current_period_start    TIMESTAMP NOT NULL,
    current_period_end      TIMESTAMP NOT NULL,
    trial_start             TIMESTAMP,
    trial_end               TIMESTAMP,
    canceled_at             TIMESTAMP,
    cancel_at_period_end    BOOLEAN NOT NULL DEFAULT FALSE,
    payment_method_id       VARCHAR(64),
    metadata                JSONB,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_subscriptions_tenant       ON subscriptions(tenant_id);
CREATE INDEX idx_subscriptions_customer     ON subscriptions(customer_id);
CREATE INDEX idx_subscriptions_status       ON subscriptions(status);
CREATE INDEX idx_subscriptions_period_end   ON subscriptions(current_period_end);

-- ---------------------------------------------------------------------------
-- invoices — generated billing documents
-- ---------------------------------------------------------------------------
CREATE TABLE invoices (
    id                  VARCHAR(64) PRIMARY KEY,      -- inv_xxx
    tenant_id           VARCHAR(64) NOT NULL,
    subscription_id     VARCHAR(64) REFERENCES subscriptions(id),
    customer_id         VARCHAR(64) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    currency            VARCHAR(3) NOT NULL,
    subtotal            BIGINT NOT NULL DEFAULT 0,
    tax                 BIGINT NOT NULL DEFAULT 0,
    total               BIGINT NOT NULL DEFAULT 0,
    amount_paid         BIGINT NOT NULL DEFAULT 0,
    amount_due          BIGINT NOT NULL DEFAULT 0,
    payment_id          VARCHAR(64),
    due_date            TIMESTAMP,
    paid_at             TIMESTAMP,
    period_start        TIMESTAMP,
    period_end          TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_invoices_tenant        ON invoices(tenant_id);
CREATE INDEX idx_invoices_subscription  ON invoices(subscription_id);
CREATE INDEX idx_invoices_status        ON invoices(status);
CREATE INDEX idx_invoices_customer      ON invoices(customer_id);

-- ---------------------------------------------------------------------------
-- invoice_line_items — individual charges/credits on an invoice
-- ---------------------------------------------------------------------------
CREATE TABLE invoice_line_items (
    id              VARCHAR(64) PRIMARY KEY,           -- ili_xxx
    invoice_id      VARCHAR(64) NOT NULL REFERENCES invoices(id),
    tenant_id       VARCHAR(64) NOT NULL,
    description     VARCHAR(512) NOT NULL,
    amount          BIGINT NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    quantity        INTEGER DEFAULT 1,
    proration       BOOLEAN NOT NULL DEFAULT FALSE,
    period_start    TIMESTAMP,
    period_end      TIMESTAMP
);

CREATE INDEX idx_line_items_invoice ON invoice_line_items(invoice_id);

-- ---------------------------------------------------------------------------
-- dunning_attempts — payment retry tracking
-- ---------------------------------------------------------------------------
CREATE TABLE dunning_attempts (
    id                  VARCHAR(64) PRIMARY KEY,       -- dun_xxx
    subscription_id     VARCHAR(64) NOT NULL REFERENCES subscriptions(id),
    invoice_id          VARCHAR(64) NOT NULL REFERENCES invoices(id),
    tenant_id           VARCHAR(64) NOT NULL,
    attempt_number      INTEGER NOT NULL,
    payment_id          VARCHAR(64),
    status              VARCHAR(16) NOT NULL,          -- PENDING, SUCCESS, FAILED
    scheduled_at        TIMESTAMP NOT NULL,
    attempted_at        TIMESTAMP,
    failure_reason      VARCHAR(256),
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dunning_subscription   ON dunning_attempts(subscription_id);
CREATE INDEX idx_dunning_scheduled      ON dunning_attempts(scheduled_at) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- Row-Level Security
-- ---------------------------------------------------------------------------
ALTER TABLE products ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_products ON products USING (tenant_id = current_tenant_id());

ALTER TABLE prices ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_prices ON prices USING (tenant_id = current_tenant_id());

ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_subscriptions ON subscriptions USING (tenant_id = current_tenant_id());

ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_invoices ON invoices USING (tenant_id = current_tenant_id());

ALTER TABLE invoice_line_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_line_items ON invoice_line_items USING (tenant_id = current_tenant_id());

ALTER TABLE dunning_attempts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_dunning ON dunning_attempts USING (tenant_id = current_tenant_id());

-- ---------------------------------------------------------------------------
-- Grants (nexuspay_app role created in V2001)
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON products TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE ON prices TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE ON subscriptions TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE ON invoices TO nexuspay_app;
GRANT SELECT, INSERT ON invoice_line_items TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE ON dunning_attempts TO nexuspay_app;
