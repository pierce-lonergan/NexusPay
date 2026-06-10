-- Merchant-registered webhook endpoints for event delivery
CREATE TABLE webhook_endpoints (
    id          VARCHAR(64) PRIMARY KEY,
    url         VARCHAR(512) NOT NULL,
    description VARCHAR(256),
    secret      VARCHAR(256) NOT NULL,
    events      JSONB        NOT NULL,  -- JSON array, e.g. ["payment.captured","refund.completed"]; Hibernate maps List<String> as JSON
    tenant_id   VARCHAR(64)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_webhook_endpoints_tenant ON webhook_endpoints(tenant_id) WHERE enabled = TRUE;
