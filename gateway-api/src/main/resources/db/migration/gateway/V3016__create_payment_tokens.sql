-- Sprint 3.5: Payment tokens for PCI-compliant card tokenization.
-- Tokens store encrypted payment method data with card metadata.
-- Single-use tokens expire after 15 minutes; multi-use after 365 days.

CREATE TABLE payment_tokens (
    id                VARCHAR(64)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    session_id        VARCHAR(64)  NOT NULL REFERENCES payment_sessions(id),
    type              VARCHAR(20)  NOT NULL,
    card_last_four    VARCHAR(4),
    card_brand        VARCHAR(20),
    card_exp_month    INT,
    card_exp_year     INT,
    card_fingerprint  VARCHAR(64),
    token_data        BYTEA,
    encryption_key_id VARCHAR(64),
    single_use        BOOLEAN      NOT NULL DEFAULT TRUE,
    used              BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at        TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_payment_tokens_type CHECK (type IN ('card', 'apple_pay', 'google_pay', 'bank_redirect', 'bnpl'))
);

-- Lookup tokens by session
CREATE INDEX idx_payment_tokens_session ON payment_tokens(session_id);

-- Customer token lookup (for returning customers)
CREATE INDEX idx_payment_tokens_tenant_customer
    ON payment_tokens(tenant_id, card_fingerprint)
    WHERE card_fingerprint IS NOT NULL;

-- Dedup multi-use tokens by fingerprint
CREATE UNIQUE INDEX uk_payment_tokens_fingerprint
    ON payment_tokens(tenant_id, card_fingerprint)
    WHERE single_use = FALSE AND card_fingerprint IS NOT NULL;

-- Row-level security for tenant isolation
ALTER TABLE payment_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_payment_tokens ON payment_tokens
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
