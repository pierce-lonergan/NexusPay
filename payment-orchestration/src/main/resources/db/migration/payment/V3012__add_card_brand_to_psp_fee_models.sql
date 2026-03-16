-- Sprint 3.3 Gap Patch (GAP-049): Add card-brand-specific pricing columns to psp_fee_models.
-- Fee models can now be scoped to card brand, card type, and domestic/international.
-- When multiple models match, the most specific one is used (brand+type+domestic > brand > default).

ALTER TABLE psp_fee_models ADD COLUMN card_brand VARCHAR(20);
ALTER TABLE psp_fee_models ADD COLUMN card_type VARCHAR(20);
ALTER TABLE psp_fee_models ADD COLUMN is_domestic BOOLEAN;

COMMENT ON COLUMN psp_fee_models.card_brand IS 'Card network (e.g., VISA, MASTERCARD, AMEX). NULL = any brand.';
COMMENT ON COLUMN psp_fee_models.card_type IS 'Card type (e.g., CREDIT, DEBIT, PREPAID). NULL = any type.';
COMMENT ON COLUMN psp_fee_models.is_domestic IS 'Whether fee applies to domestic (true) or international (false) transactions. NULL = both.';

CREATE INDEX idx_psp_fee_card ON psp_fee_models (psp_connector, currency, card_brand, card_type, is_domestic);

-- Drop the old unique constraint and create a new one that includes card attributes
ALTER TABLE psp_fee_models DROP CONSTRAINT IF EXISTS uk_psp_fee;
ALTER TABLE psp_fee_models ADD CONSTRAINT uk_psp_fee_card
    UNIQUE (tenant_id, psp_connector, currency, effective_from, card_brand, card_type, is_domestic);

-- Seed card-brand-specific fee models for development
-- Stripe charges more for Amex
INSERT INTO psp_fee_models (tenant_id, psp_connector, fee_type, per_tx_fee, percentage_fee, currency, effective_from, card_brand)
VALUES ('default', 'stripe', 'BLENDED', 0.30, 0.035000, 'USD', '2026-01-01', 'AMEX');

-- Adyen has lower rates for domestic debit
INSERT INTO psp_fee_models (tenant_id, psp_connector, fee_type, per_tx_fee, percentage_fee, currency, effective_from, card_brand, card_type, is_domestic)
VALUES ('default', 'adyen', 'BLENDED', 0.08, 0.018000, 'USD', '2026-01-01', 'VISA', 'DEBIT', true);

-- Adyen international credit is more expensive
INSERT INTO psp_fee_models (tenant_id, psp_connector, fee_type, per_tx_fee, percentage_fee, currency, effective_from, card_brand, card_type, is_domestic)
VALUES ('default', 'adyen', 'BLENDED', 0.15, 0.032000, 'USD', '2026-01-01', 'VISA', 'CREDIT', false);
