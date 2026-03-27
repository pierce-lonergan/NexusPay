-- Sprint 3.6: PSP health snapshot table for composite scoring and anomaly tracking

CREATE TABLE analytics.psp_health_snapshots (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36) NOT NULL,
    psp_connector     VARCHAR(50) NOT NULL,
    snapshot_time     TIMESTAMPTZ NOT NULL,
    health_score      INTEGER NOT NULL,
    auth_rate_score   INTEGER NOT NULL,
    latency_score     INTEGER NOT NULL,
    error_rate_score  INTEGER NOT NULL,
    auth_rate_7d      DECIMAL(8,6),
    avg_latency_ms    INTEGER,
    p95_latency_ms    INTEGER,
    error_rate        DECIMAL(8,6),
    anomaly_detected  BOOLEAN NOT NULL DEFAULT false,
    anomaly_details   JSONB,
    CONSTRAINT uk_psp_health UNIQUE (tenant_id, psp_connector, snapshot_time)
);

CREATE INDEX idx_psp_health_time ON analytics.psp_health_snapshots (tenant_id, psp_connector, snapshot_time DESC);

ALTER TABLE analytics.psp_health_snapshots ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics.psp_health_snapshots
    USING (tenant_id = current_setting('app.current_tenant_id'));
