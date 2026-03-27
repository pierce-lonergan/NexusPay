-- Sprint 3.6: Decline analysis rollup table (daily)

CREATE TABLE analytics.decline_daily (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    bucket_date       DATE NOT NULL,
    psp_connector     VARCHAR(50) NOT NULL,
    decline_code      VARCHAR(50) NOT NULL,
    decline_category  VARCHAR(20) NOT NULL,
    card_brand        VARCHAR(20),
    issuing_region    VARCHAR(2),
    issuer_name       VARCHAR(100),
    total_count       INTEGER NOT NULL DEFAULT 0,
    total_volume      DECIMAL(18,2) NOT NULL DEFAULT 0,
    CONSTRAINT uk_decline_daily UNIQUE (tenant_id, bucket_date, psp_connector, decline_code, card_brand, issuing_region, issuer_name)
);

CREATE INDEX idx_decline_daily_tenant ON analytics.decline_daily (tenant_id, bucket_date);
CREATE INDEX idx_decline_daily_code ON analytics.decline_daily (decline_code, bucket_date);

ALTER TABLE analytics.decline_daily ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics.decline_daily
    USING (tenant_id = current_setting('app.current_tenant_id'));
