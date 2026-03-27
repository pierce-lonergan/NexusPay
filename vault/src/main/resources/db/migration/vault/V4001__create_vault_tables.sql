-- Card vault tables for PCI-compliant PAN storage and network tokenization
-- Sprint 4.1: Universal Card Vault & Network Tokenization

-- Encrypted card storage
CREATE TABLE vaulted_cards (
    id                  VARCHAR(64)  PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    encrypted_pan       BYTEA        NOT NULL,          -- AES-256-GCM encrypted
    pan_last4           VARCHAR(4)   NOT NULL,
    pan_bin             VARCHAR(8)   NOT NULL,           -- First 6-8 digits for routing
    brand               VARCHAR(16)  NOT NULL,           -- VISA, MASTERCARD, AMEX, DISCOVER
    exp_month           SMALLINT     NOT NULL,
    exp_year            SMALLINT     NOT NULL,
    cardholder_name     VARCHAR(256),
    encryption_key_id   VARCHAR(64)  NOT NULL,           -- References key in HSM/Vault
    fingerprint         VARCHAR(128) NOT NULL,           -- SHA-256 of PAN for dedup
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_vaulted_cards_tenant_fingerprint UNIQUE (tenant_id, fingerprint)
);

CREATE INDEX idx_vaulted_cards_tenant ON vaulted_cards (tenant_id);
CREATE INDEX idx_vaulted_cards_key_id ON vaulted_cards (encryption_key_id);

-- Network tokens (Visa VTS, Mastercard MDES, Amex)
CREATE TABLE network_tokens (
    id                  VARCHAR(64)  PRIMARY KEY,
    vaulted_card_id     VARCHAR(64)  NOT NULL REFERENCES vaulted_cards(id),
    tenant_id           VARCHAR(64)  NOT NULL,
    network             VARCHAR(16)  NOT NULL,           -- VISA_VTS, MC_MDES, AMEX
    token_reference     VARCHAR(256) NOT NULL,           -- Network-assigned token reference
    token_last4         VARCHAR(4),
    status              VARCHAR(16)  NOT NULL DEFAULT 'PROVISIONED',
    token_expiry        VARCHAR(4),                      -- MMYY
    provisioned_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at        TIMESTAMPTZ,
    suspended_at        TIMESTAMPTZ
);

CREATE INDEX idx_network_tokens_card ON network_tokens (vaulted_card_id);
CREATE INDEX idx_network_tokens_tenant ON network_tokens (tenant_id);

-- Merchant-facing vault tokens (tok_xxx)
CREATE TABLE vault_tokens (
    id                  VARCHAR(64)  PRIMARY KEY,        -- tok_xxx
    vaulted_card_id     VARCHAR(64)  NOT NULL REFERENCES vaulted_cards(id),
    tenant_id           VARCHAR(64)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_vault_tokens_card ON vault_tokens (vaulted_card_id);
CREATE INDEX idx_vault_tokens_tenant ON vault_tokens (tenant_id);

-- Vault-to-vault migration tracking
CREATE TABLE vault_migrations (
    id                  VARCHAR(64)  PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    source_provider     VARCHAR(32)  NOT NULL,           -- SPREEDLY, STRIPE, BRAINTREE
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    total_cards         INTEGER      DEFAULT 0,
    migrated_count      INTEGER      DEFAULT 0,
    failed_count        INTEGER      DEFAULT 0,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_vault_migrations_tenant_status ON vault_migrations (tenant_id, status);

-- Row-Level Security
ALTER TABLE vaulted_cards ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_vaulted_cards ON vaulted_cards
    USING (tenant_id = current_setting('app.current_tenant_id', true));

ALTER TABLE network_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_network_tokens ON network_tokens
    USING (tenant_id = current_setting('app.current_tenant_id', true));

ALTER TABLE vault_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_vault_tokens ON vault_tokens
    USING (tenant_id = current_setting('app.current_tenant_id', true));

ALTER TABLE vault_migrations ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_vault_migrations ON vault_migrations
    USING (tenant_id = current_setting('app.current_tenant_id', true));

-- Grant permissions to application role
GRANT SELECT, INSERT, UPDATE, DELETE ON vaulted_cards TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON network_tokens TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON vault_tokens TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON vault_migrations TO nexuspay_app;
