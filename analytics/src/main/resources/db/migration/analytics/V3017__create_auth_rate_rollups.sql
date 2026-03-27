-- Sprint 3.6: Authorization rate rollup tables (hourly, daily, monthly)

CREATE SCHEMA IF NOT EXISTS analytics;

-- Hourly rollup
CREATE TABLE analytics.auth_rate_hourly (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    bucket_hour       TIMESTAMPTZ NOT NULL,
    psp_connector     VARCHAR(50) NOT NULL,
    card_brand        VARCHAR(20),
    card_type         VARCHAR(10),
    issuing_region    VARCHAR(2),
    currency          VARCHAR(3),
    payment_method    VARCHAR(30),
    total_attempts    INTEGER NOT NULL DEFAULT 0,
    total_approved    INTEGER NOT NULL DEFAULT 0,
    total_declined    INTEGER NOT NULL DEFAULT 0,
    total_errors      INTEGER NOT NULL DEFAULT 0,
    auth_rate         DECIMAL(8,6) NOT NULL DEFAULT 0,
    avg_latency_ms    INTEGER,
    p50_latency_ms    INTEGER,
    p95_latency_ms    INTEGER,
    p99_latency_ms    INTEGER,
    CONSTRAINT uk_auth_hourly UNIQUE (tenant_id, bucket_hour, psp_connector, card_brand, card_type, issuing_region, currency, payment_method)
);

CREATE INDEX idx_auth_hourly_tenant_time ON analytics.auth_rate_hourly (tenant_id, bucket_hour);
CREATE INDEX idx_auth_hourly_psp ON analytics.auth_rate_hourly (psp_connector, bucket_hour);

-- Daily rollup (materialized from hourly)
CREATE TABLE analytics.auth_rate_daily (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    bucket_date       DATE NOT NULL,
    psp_connector     VARCHAR(50) NOT NULL,
    card_brand        VARCHAR(20),
    card_type         VARCHAR(10),
    issuing_region    VARCHAR(2),
    currency          VARCHAR(3),
    payment_method    VARCHAR(30),
    total_attempts    INTEGER NOT NULL DEFAULT 0,
    total_approved    INTEGER NOT NULL DEFAULT 0,
    total_declined    INTEGER NOT NULL DEFAULT 0,
    total_errors      INTEGER NOT NULL DEFAULT 0,
    auth_rate         DECIMAL(8,6) NOT NULL DEFAULT 0,
    avg_latency_ms    INTEGER,
    p95_latency_ms    INTEGER,
    CONSTRAINT uk_auth_daily UNIQUE (tenant_id, bucket_date, psp_connector, card_brand, card_type, issuing_region, currency, payment_method)
);

-- Monthly rollup (materialized from daily)
CREATE TABLE analytics.auth_rate_monthly (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    bucket_month      DATE NOT NULL,
    psp_connector     VARCHAR(50) NOT NULL,
    card_brand        VARCHAR(20),
    card_type         VARCHAR(10),
    issuing_region    VARCHAR(2),
    currency          VARCHAR(3),
    payment_method    VARCHAR(30),
    total_attempts    INTEGER NOT NULL DEFAULT 0,
    total_approved    INTEGER NOT NULL DEFAULT 0,
    total_declined    INTEGER NOT NULL DEFAULT 0,
    total_errors      INTEGER NOT NULL DEFAULT 0,
    auth_rate         DECIMAL(8,6) NOT NULL DEFAULT 0,
    CONSTRAINT uk_auth_monthly UNIQUE (tenant_id, bucket_month, psp_connector, card_brand, card_type, issuing_region, currency, payment_method)
);

ALTER TABLE analytics.auth_rate_hourly ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics.auth_rate_hourly
    USING (tenant_id = current_setting('app.current_tenant_id'));

ALTER TABLE analytics.auth_rate_daily ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics.auth_rate_daily
    USING (tenant_id = current_setting('app.current_tenant_id'));

ALTER TABLE analytics.auth_rate_monthly ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics.auth_rate_monthly
    USING (tenant_id = current_setting('app.current_tenant_id'));
