-- Device fingerprints with reputation scoring for fraud detection
-- Sprint 3.1: Fraud Prevention

CREATE TABLE device_fingerprints (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(36)  NOT NULL,
    fingerprint_hash  VARCHAR(64)  NOT NULL,      -- SHA-256 of composite fingerprint
    customer_id       VARCHAR(64),
    browser_family    VARCHAR(50),
    os_family         VARCHAR(50),
    device_type       VARCHAR(20),                -- DESKTOP, MOBILE, TABLET
    screen_resolution VARCHAR(20),
    timezone_offset   INTEGER,
    language          VARCHAR(10),
    ip_address        INET,
    ip_country        VARCHAR(2),                 -- ISO 3166-1 alpha-2
    ip_city           VARCHAR(100),
    reputation_score  INTEGER      NOT NULL DEFAULT 50,  -- 0 (malicious) to 100 (trusted)
    first_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    times_seen        INTEGER      NOT NULL DEFAULT 1,
    flagged           BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT uk_device_fp_tenant_hash UNIQUE (tenant_id, fingerprint_hash)
);

CREATE INDEX idx_device_fp_customer ON device_fingerprints (tenant_id, customer_id);
CREATE INDEX idx_device_fp_ip ON device_fingerprints (ip_address);
CREATE INDEX idx_device_fp_reputation ON device_fingerprints (tenant_id, reputation_score)
    WHERE reputation_score < 30;

-- Row-Level Security
ALTER TABLE device_fingerprints ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_device_fp ON device_fingerprints
    USING (tenant_id = current_setting('app.current_tenant_id', true));
