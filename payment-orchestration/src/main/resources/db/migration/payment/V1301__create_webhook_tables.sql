-- Inbound webhooks: raw payload persistence for audit and reprocessing
CREATE TABLE inbound_webhooks (
    id          VARCHAR(64) PRIMARY KEY,
    event_id    VARCHAR(128) UNIQUE NOT NULL,
    event_type  VARCHAR(64) NOT NULL,
    raw_payload JSONB NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP,
    status      VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'default'
);

CREATE INDEX idx_inbound_webhooks_event_type ON inbound_webhooks(event_type);
CREATE INDEX idx_inbound_webhooks_status ON inbound_webhooks(status);
CREATE INDEX idx_inbound_webhooks_received_at ON inbound_webhooks(received_at);

-- Transactional outbox: guarantees at-least-once event delivery to Kafka
CREATE TABLE event_outbox (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id   VARCHAR(64) NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    payload        JSONB NOT NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMP,
    tenant_id      VARCHAR(64) NOT NULL DEFAULT 'default'
);

CREATE INDEX idx_outbox_unpublished ON event_outbox(created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate ON event_outbox(aggregate_type, aggregate_id);
