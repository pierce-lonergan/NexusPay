-- B2B payment tables for purchase orders, virtual cards, invoices, and vendor payments
-- Sprint 4.3: B2B Payments

-- Purchase orders
CREATE TABLE purchase_orders (
    id                  VARCHAR(64)    PRIMARY KEY,
    tenant_id           VARCHAR(64)    NOT NULL,
    buyer_id            VARCHAR(64)    NOT NULL,
    seller_id           VARCHAR(64)    NOT NULL,
    po_number           VARCHAR(64)    NOT NULL,
    amount              BIGINT         NOT NULL DEFAULT 0,
    tax_amount          BIGINT         NOT NULL DEFAULT 0,
    currency            VARCHAR(3)     NOT NULL,
    status              VARCHAR(16)    NOT NULL DEFAULT 'DRAFT',
    terms               VARCHAR(16),
    line_items          JSONB          NOT NULL DEFAULT '[]',
    due_date            DATE,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_purchase_orders_tenant ON purchase_orders (tenant_id);
CREATE INDEX idx_purchase_orders_buyer ON purchase_orders (tenant_id, buyer_id);
CREATE INDEX idx_purchase_orders_seller ON purchase_orders (tenant_id, seller_id);
CREATE INDEX idx_purchase_orders_status ON purchase_orders (tenant_id, status);

-- B2B invoices
CREATE TABLE b2b_invoices (
    id                  VARCHAR(64)    PRIMARY KEY,
    tenant_id           VARCHAR(64)    NOT NULL,
    purchase_order_id   VARCHAR(64)    REFERENCES purchase_orders(id),
    buyer_id            VARCHAR(64)    NOT NULL,
    seller_id           VARCHAR(64)    NOT NULL,
    invoice_number      VARCHAR(64)    NOT NULL,
    amount              BIGINT         NOT NULL,
    tax_amount          BIGINT         NOT NULL DEFAULT 0,
    currency            VARCHAR(3)     NOT NULL,
    status              VARCHAR(16)    NOT NULL DEFAULT 'DRAFT',
    terms               VARCHAR(16),
    due_date            DATE,
    paid_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_b2b_invoices_tenant ON b2b_invoices (tenant_id);
CREATE INDEX idx_b2b_invoices_po ON b2b_invoices (purchase_order_id);
CREATE INDEX idx_b2b_invoices_status ON b2b_invoices (tenant_id, status);

-- Virtual cards
CREATE TABLE virtual_cards (
    id                      VARCHAR(64)    PRIMARY KEY,
    tenant_id               VARCHAR(64)    NOT NULL,
    issuing_provider        VARCHAR(32)    NOT NULL,
    external_card_id        VARCHAR(128),
    card_last4              VARCHAR(4),
    card_type               VARCHAR(16)    NOT NULL,
    amount_limit            BIGINT         NOT NULL,
    currency                VARCHAR(3)     NOT NULL,
    merchant_category_codes TEXT[],
    expires_at              TIMESTAMPTZ    NOT NULL,
    status                  VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',
    spent_amount            BIGINT         NOT NULL DEFAULT 0,
    purchase_order_id       VARCHAR(64)    REFERENCES purchase_orders(id),
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_virtual_cards_tenant ON virtual_cards (tenant_id);
CREATE INDEX idx_virtual_cards_status ON virtual_cards (tenant_id, status);
CREATE INDEX idx_virtual_cards_po ON virtual_cards (purchase_order_id);

-- Vendor payments
CREATE TABLE vendor_payments (
    id                  VARCHAR(64)    PRIMARY KEY,
    tenant_id           VARCHAR(64)    NOT NULL,
    vendor_id           VARCHAR(64)    NOT NULL,
    amount              BIGINT         NOT NULL,
    currency            VARCHAR(3)     NOT NULL,
    method              VARCHAR(16)    NOT NULL,
    status              VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    batch_id            VARCHAR(64),
    remittance_info     TEXT,
    scheduled_at        TIMESTAMPTZ,
    paid_at             TIMESTAMPTZ,
    external_reference  VARCHAR(128),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_vendor_payments_tenant ON vendor_payments (tenant_id);
CREATE INDEX idx_vendor_payments_vendor ON vendor_payments (vendor_id);
CREATE INDEX idx_vendor_payments_batch ON vendor_payments (batch_id);
CREATE INDEX idx_vendor_payments_status ON vendor_payments (tenant_id, status);

-- Row-Level Security
ALTER TABLE purchase_orders ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_purchase_orders ON purchase_orders
    USING (tenant_id = current_setting('app.current_tenant_id', true));

ALTER TABLE b2b_invoices ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_b2b_invoices ON b2b_invoices
    USING (tenant_id = current_setting('app.current_tenant_id', true));

ALTER TABLE virtual_cards ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_virtual_cards ON virtual_cards
    USING (tenant_id = current_setting('app.current_tenant_id', true));

ALTER TABLE vendor_payments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_vendor_payments ON vendor_payments
    USING (tenant_id = current_setting('app.current_tenant_id', true));

-- Grant permissions to application role
GRANT SELECT, INSERT, UPDATE, DELETE ON purchase_orders TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON b2b_invoices TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON virtual_cards TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON vendor_payments TO nexuspay_app;
