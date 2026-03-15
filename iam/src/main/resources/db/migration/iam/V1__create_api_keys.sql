-- API keys: Stripe-inspired sk_test_ / sk_live_ authentication
CREATE TABLE api_keys (
    id          VARCHAR(64) PRIMARY KEY,
    key_hash    VARCHAR(256) NOT NULL,
    key_prefix  VARCHAR(16) NOT NULL,
    name        VARCHAR(128),
    role        VARCHAR(32) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'default',
    is_live     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    revoked_at  TIMESTAMP,

    CONSTRAINT chk_api_key_role CHECK (role IN ('admin', 'operator', 'viewer'))
);

CREATE INDEX idx_api_keys_prefix ON api_keys(key_prefix);
CREATE INDEX idx_api_keys_tenant ON api_keys(tenant_id);
CREATE INDEX idx_api_keys_active ON api_keys(key_prefix) WHERE revoked_at IS NULL;
