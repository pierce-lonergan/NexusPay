-- =============================================================================
-- V2004: Dispute Management Schema
-- Sprint 2.4 — Dispute lifecycle, evidence tracking, event timeline
-- =============================================================================

-- ---------------------------------------------------------------------------
-- disputes — aggregate root for chargeback/dispute lifecycle
-- ---------------------------------------------------------------------------
CREATE TABLE disputes (
    id                   VARCHAR(64) PRIMARY KEY,         -- dp_xxx
    tenant_id            VARCHAR(64) NOT NULL,
    payment_id           VARCHAR(64) NOT NULL,
    external_dispute_id  VARCHAR(128),                    -- PSP/network reference
    reason_code          VARCHAR(32) NOT NULL,
    reason_description   TEXT,
    amount               BIGINT NOT NULL,                 -- minor units
    currency             VARCHAR(3) NOT NULL,             -- ISO 4217
    status               VARCHAR(32) NOT NULL DEFAULT 'OPENED',
    network              VARCHAR(16),                     -- VISA, MASTERCARD, AMEX
    evidence_due_date    TIMESTAMP,
    evidence_submitted_at TIMESTAMP,
    resolved_at          TIMESTAMP,
    outcome              VARCHAR(16),                     -- WON, LOST
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_disputes_tenant     ON disputes(tenant_id);
CREATE INDEX idx_disputes_payment    ON disputes(payment_id);
CREATE INDEX idx_disputes_status     ON disputes(status);
CREATE INDEX idx_disputes_due_date   ON disputes(evidence_due_date) WHERE status = 'EVIDENCE_NEEDED';

-- ---------------------------------------------------------------------------
-- dispute_evidence — uploaded evidence files per dispute
-- ---------------------------------------------------------------------------
CREATE TABLE dispute_evidence (
    id              VARCHAR(64) PRIMARY KEY,              -- dpe_xxx
    dispute_id      VARCHAR(64) NOT NULL REFERENCES disputes(id),
    tenant_id       VARCHAR(64) NOT NULL,
    evidence_type   VARCHAR(32) NOT NULL,
    file_key        VARCHAR(256),                         -- S3/MinIO object key
    file_name       VARCHAR(256),
    file_size       BIGINT,
    description     TEXT,
    uploaded_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_evidence_dispute ON dispute_evidence(dispute_id);

-- ---------------------------------------------------------------------------
-- dispute_events — immutable audit timeline per dispute
-- ---------------------------------------------------------------------------
CREATE TABLE dispute_events (
    id          VARCHAR(64) PRIMARY KEY,                  -- dpev_xxx
    dispute_id  VARCHAR(64) NOT NULL REFERENCES disputes(id),
    tenant_id   VARCHAR(64) NOT NULL,
    event_type  VARCHAR(32) NOT NULL,
    old_status  VARCHAR(32),
    new_status  VARCHAR(32),
    actor       VARCHAR(128),
    details     JSONB,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dispute_events_dispute ON dispute_events(dispute_id);

-- ---------------------------------------------------------------------------
-- Row-Level Security
-- ---------------------------------------------------------------------------
ALTER TABLE disputes ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_disputes
    ON disputes USING (tenant_id = current_tenant_id());

ALTER TABLE dispute_evidence ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_evidence
    ON dispute_evidence USING (tenant_id = current_tenant_id());

ALTER TABLE dispute_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_dispute_events
    ON dispute_events USING (tenant_id = current_tenant_id());

-- ---------------------------------------------------------------------------
-- Grants (nexuspay_app role created in V2001)
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON disputes TO nexuspay_app;
GRANT SELECT, INSERT ON dispute_evidence TO nexuspay_app;
GRANT SELECT, INSERT ON dispute_events TO nexuspay_app;
