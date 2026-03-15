-- Sprint 3.2: PSP currency capabilities for currency-aware routing
-- Maps each PSP connector to its supported currencies and features.

CREATE TABLE currency_capabilities (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    psp_connector         VARCHAR(50) NOT NULL,
    currency_code         VARCHAR(3) NOT NULL,
    supports_presentment  BOOLEAN NOT NULL DEFAULT true,
    supports_settlement   BOOLEAN NOT NULL DEFAULT false,
    supports_dcc          BOOLEAN NOT NULL DEFAULT false,
    min_amount            DECIMAL(18,2),
    max_amount            DECIMAL(18,2),
    enabled               BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT uk_currency_cap UNIQUE (psp_connector, currency_code)
);

CREATE INDEX idx_currency_cap_psp ON currency_capabilities (psp_connector, enabled);
CREATE INDEX idx_currency_cap_currency ON currency_capabilities (currency_code, enabled);

-- Seed common PSP capabilities for development
INSERT INTO currency_capabilities (id, psp_connector, currency_code, supports_presentment, supports_settlement, supports_dcc) VALUES
    (gen_random_uuid(), 'stripe', 'USD', true, true, true),
    (gen_random_uuid(), 'stripe', 'EUR', true, true, true),
    (gen_random_uuid(), 'stripe', 'GBP', true, true, true),
    (gen_random_uuid(), 'stripe', 'JPY', true, true, false),
    (gen_random_uuid(), 'stripe', 'CAD', true, true, false),
    (gen_random_uuid(), 'stripe', 'AUD', true, true, false),
    (gen_random_uuid(), 'adyen', 'USD', true, true, true),
    (gen_random_uuid(), 'adyen', 'EUR', true, true, true),
    (gen_random_uuid(), 'adyen', 'GBP', true, true, true),
    (gen_random_uuid(), 'adyen', 'JPY', true, true, true),
    (gen_random_uuid(), 'adyen', 'CNY', true, false, false),
    (gen_random_uuid(), 'adyen', 'INR', true, false, false),
    (gen_random_uuid(), 'dummy_connector', 'USD', true, true, false),
    (gen_random_uuid(), 'dummy_connector', 'EUR', true, true, false),
    (gen_random_uuid(), 'dummy_connector', 'GBP', true, true, false);
