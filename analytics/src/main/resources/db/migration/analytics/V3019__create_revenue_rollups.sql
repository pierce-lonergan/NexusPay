-- Sprint 3.6: Revenue rollup tables (hourly, daily)

CREATE TABLE analytics.revenue_hourly (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    bucket_hour       TIMESTAMPTZ NOT NULL,
    psp_connector     VARCHAR(50),
    currency          VARCHAR(3) NOT NULL,
    payment_method    VARCHAR(30),
    total_volume      DECIMAL(18,2) NOT NULL DEFAULT 0,
    total_count       INTEGER NOT NULL DEFAULT 0,
    total_fees        DECIMAL(18,4) NOT NULL DEFAULT 0,
    net_revenue       DECIMAL(18,4) NOT NULL DEFAULT 0,
    refund_volume     DECIMAL(18,2) NOT NULL DEFAULT 0,
    refund_count      INTEGER NOT NULL DEFAULT 0,
    chargeback_volume DECIMAL(18,2) NOT NULL DEFAULT 0,
    chargeback_count  INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_revenue_hourly UNIQUE (tenant_id, bucket_hour, psp_connector, currency, payment_method)
);

CREATE TABLE analytics.revenue_daily (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    bucket_date       DATE NOT NULL,
    psp_connector     VARCHAR(50),
    currency          VARCHAR(3) NOT NULL,
    payment_method    VARCHAR(30),
    total_volume      DECIMAL(18,2) NOT NULL DEFAULT 0,
    total_count       INTEGER NOT NULL DEFAULT 0,
    total_fees        DECIMAL(18,4) NOT NULL DEFAULT 0,
    net_revenue       DECIMAL(18,4) NOT NULL DEFAULT 0,
    refund_volume     DECIMAL(18,2) NOT NULL DEFAULT 0,
    refund_count      INTEGER NOT NULL DEFAULT 0,
    chargeback_volume DECIMAL(18,2) NOT NULL DEFAULT 0,
    chargeback_count  INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_revenue_daily UNIQUE (tenant_id, bucket_date, psp_connector, currency, payment_method)
);

ALTER TABLE analytics.revenue_hourly ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics.revenue_hourly
    USING (tenant_id = current_setting('app.current_tenant_id'));

ALTER TABLE analytics.revenue_daily ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics.revenue_daily
    USING (tenant_id = current_setting('app.current_tenant_id'));
