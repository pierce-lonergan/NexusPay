-- ==========================================================================
-- V2003: Reconciliation Engine Schema (Sprint 2.3)
--
-- Three tables for settlement file reconciliation:
-- 1. reconciliation_runs — batch reconciliation execution tracking
-- 2. settlement_records — parsed settlement line items from PSP files
-- 3. reconciliation_exceptions — unresolved discrepancies requiring action
--
-- All tables are RLS-enabled for multi-tenant isolation.
-- ==========================================================================

-- Reconciliation Runs
CREATE TABLE reconciliation_runs (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    file_name VARCHAR(256),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    total_records INTEGER DEFAULT 0,
    matched_count INTEGER DEFAULT 0,
    unmatched_count INTEGER DEFAULT 0,
    exception_count INTEGER DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE reconciliation_runs IS 'Tracks each reconciliation batch execution';
COMMENT ON COLUMN reconciliation_runs.status IS 'PENDING, RUNNING, COMPLETED, FAILED';

-- Settlement Records
CREATE TABLE settlement_records (
    id VARCHAR(64) PRIMARY KEY,
    reconciliation_run_id VARCHAR(64) NOT NULL REFERENCES reconciliation_runs(id),
    tenant_id VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    external_id VARCHAR(128) NOT NULL,
    payment_reference VARCHAR(64),
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    fee_amount BIGINT DEFAULT 0,
    net_amount BIGINT NOT NULL,
    settled_at TIMESTAMP NOT NULL,
    match_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    matched_payment_id VARCHAR(64),
    matched_journal_entry_id VARCHAR(64),
    raw_data JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE settlement_records IS 'Parsed settlement line items from PSP settlement files';
COMMENT ON COLUMN settlement_records.match_status IS 'PENDING, MATCHED, UNMATCHED, EXCEPTION';
COMMENT ON COLUMN settlement_records.amount IS 'Gross settlement amount in minor units';
COMMENT ON COLUMN settlement_records.net_amount IS 'Net amount after fees in minor units';

-- Reconciliation Exceptions
CREATE TABLE reconciliation_exceptions (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    reconciliation_run_id VARCHAR(64) NOT NULL REFERENCES reconciliation_runs(id),
    settlement_record_id VARCHAR(64) REFERENCES settlement_records(id),
    exception_type VARCHAR(32) NOT NULL,
    expected_amount BIGINT,
    actual_amount BIGINT,
    description TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    assigned_to VARCHAR(128),
    resolved_at TIMESTAMP,
    resolution_notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE reconciliation_exceptions IS 'Discrepancies found during reconciliation requiring human action';
COMMENT ON COLUMN reconciliation_exceptions.exception_type IS 'AMOUNT_MISMATCH, MISSING_PAYMENT, MISSING_SETTLEMENT, FEE_DISCREPANCY';
COMMENT ON COLUMN reconciliation_exceptions.status IS 'OPEN, INVESTIGATING, RESOLVED, WRITTEN_OFF';

-- Indexes
CREATE INDEX idx_settlement_records_run ON settlement_records(reconciliation_run_id);
CREATE INDEX idx_settlement_records_match ON settlement_records(match_status);
CREATE INDEX idx_settlement_records_external ON settlement_records(external_id, provider);
CREATE INDEX idx_recon_exceptions_status ON reconciliation_exceptions(status);
CREATE INDEX idx_recon_exceptions_run ON reconciliation_exceptions(reconciliation_run_id);
CREATE INDEX idx_recon_runs_tenant ON reconciliation_runs(tenant_id, created_at DESC);

-- Row-Level Security
ALTER TABLE reconciliation_runs ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_recon_runs ON reconciliation_runs
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE settlement_records ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_settlement ON settlement_records
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE reconciliation_exceptions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_recon_exceptions ON reconciliation_exceptions
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Grant privileges to the application role (created in V2001)
GRANT SELECT, INSERT, UPDATE ON reconciliation_runs TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE ON settlement_records TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE ON reconciliation_exceptions TO nexuspay_app;
