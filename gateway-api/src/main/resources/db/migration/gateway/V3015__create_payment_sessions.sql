-- Sprint 3.5: Payment sessions for client-side SDK.
-- Sessions provide restricted-scope access for checkout flows.
-- Each session has a client_secret used by the SDK to authenticate.

CREATE TABLE payment_sessions (
    id                      VARCHAR(64) PRIMARY KEY,
    tenant_id               VARCHAR(64)  NOT NULL,
    payment_intent_id       VARCHAR(64),
    client_secret           VARCHAR(128) NOT NULL,
    amount                  BIGINT       NOT NULL,
    currency                VARCHAR(3)   NOT NULL,
    status                  VARCHAR(20)  NOT NULL DEFAULT 'open',
    customer_id             VARCHAR(64),
    allowed_payment_methods JSONB        NOT NULL DEFAULT '["card"]',
    success_url             TEXT,
    cancel_url              TEXT,
    branding                JSONB,
    metadata                JSONB,
    tokenize_attempts       INT          NOT NULL DEFAULT 0,
    expires_at              TIMESTAMPTZ  NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_payment_sessions_client_secret UNIQUE (client_secret),
    CONSTRAINT chk_payment_sessions_status CHECK (status IN ('open', 'complete', 'expired'))
);

-- Lookup by tenant + open status (most common query for active sessions)
CREATE INDEX idx_payment_sessions_tenant_status
    ON payment_sessions(tenant_id, status)
    WHERE status = 'open';

-- Expiry queries (lazy expiration checks)
CREATE INDEX idx_payment_sessions_expires
    ON payment_sessions(expires_at)
    WHERE status = 'open';

-- Row-level security for tenant isolation
ALTER TABLE payment_sessions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_payment_sessions ON payment_sessions
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
