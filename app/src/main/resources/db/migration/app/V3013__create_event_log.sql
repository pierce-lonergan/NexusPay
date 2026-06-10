-- Sprint 3.4: Append-only event log for audit and replay.
-- All published events are captured here after successful Kafka publish.
-- UPDATE and DELETE are prevented at the database level via rules.

CREATE TABLE event_log (
    id              BIGSERIAL PRIMARY KEY,
    event_id        VARCHAR(128) UNIQUE NOT NULL,
    aggregate_type  VARCHAR(64) NOT NULL,
    aggregate_id    VARCHAR(64) NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    event_version   INT NOT NULL DEFAULT 1,
    payload         BYTEA NOT NULL,
    payload_format  VARCHAR(8) NOT NULL DEFAULT 'JSON',
    metadata        JSONB,
    tenant_id       VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Query patterns: by aggregate (event replay), by type+time (analytics), by tenant (isolation)
CREATE INDEX idx_event_log_aggregate ON event_log(aggregate_type, aggregate_id);
CREATE INDEX idx_event_log_type_time ON event_log(event_type, created_at);
CREATE INDEX idx_event_log_tenant ON event_log(tenant_id, created_at);

-- Row-level security for tenant isolation
ALTER TABLE event_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_event_log ON event_log
    USING (tenant_id = current_tenant_id());

-- Append-only enforcement: prevent UPDATE and DELETE at DB level
CREATE RULE no_update_event_log AS ON UPDATE TO event_log DO INSTEAD NOTHING;
CREATE RULE no_delete_event_log AS ON DELETE TO event_log DO INSTEAD NOTHING;
