-- Sprint 3.6: Materialized views for analytics aggregation

-- Daily rollup refresh from hourly auth rate data
CREATE MATERIALIZED VIEW analytics.mv_auth_rate_daily_refresh AS
SELECT
    tenant_id,
    DATE(bucket_hour) AS bucket_date,
    psp_connector,
    card_brand,
    card_type,
    issuing_region,
    currency,
    payment_method,
    SUM(total_attempts) AS total_attempts,
    SUM(total_approved) AS total_approved,
    SUM(total_declined) AS total_declined,
    SUM(total_errors) AS total_errors,
    CASE WHEN SUM(total_attempts) > 0
         THEN SUM(total_approved)::DECIMAL / SUM(total_attempts)
         ELSE 0 END AS auth_rate,
    AVG(avg_latency_ms)::INTEGER AS avg_latency_ms,
    MAX(p95_latency_ms) AS p95_latency_ms
FROM analytics.auth_rate_hourly
GROUP BY tenant_id, DATE(bucket_hour), psp_connector, card_brand, card_type,
         issuing_region, currency, payment_method;

CREATE UNIQUE INDEX ON analytics.mv_auth_rate_daily_refresh
    (tenant_id, bucket_date, psp_connector, card_brand, card_type, issuing_region, currency, payment_method);

-- PSP health trend view (last 30 days)
CREATE MATERIALIZED VIEW analytics.mv_psp_health_trend AS
SELECT
    tenant_id,
    psp_connector,
    DATE(snapshot_time) AS snapshot_date,
    AVG(health_score)::INTEGER AS avg_health_score,
    MIN(health_score) AS min_health_score,
    MAX(health_score) AS max_health_score,
    BOOL_OR(anomaly_detected) AS had_anomaly
FROM analytics.psp_health_snapshots
WHERE snapshot_time > now() - INTERVAL '30 days'
GROUP BY tenant_id, psp_connector, DATE(snapshot_time);

CREATE UNIQUE INDEX ON analytics.mv_psp_health_trend (tenant_id, psp_connector, snapshot_date);
