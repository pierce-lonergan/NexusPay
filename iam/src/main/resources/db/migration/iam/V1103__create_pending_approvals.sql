-- Pending approvals: maker-checker workflow for sensitive operations
CREATE TABLE pending_approvals (
    id              VARCHAR(64) PRIMARY KEY,
    action          VARCHAR(64) NOT NULL,
    resource_type   VARCHAR(64) NOT NULL,
    resource_id     VARCHAR(64),
    payload         JSONB NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    requested_by    VARCHAR(128) NOT NULL,
    reviewed_by     VARCHAR(128),
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'default',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    reviewed_at     TIMESTAMP,

    CONSTRAINT chk_approval_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE INDEX idx_approvals_status ON pending_approvals(status);
CREATE INDEX idx_approvals_tenant ON pending_approvals(tenant_id);
CREATE INDEX idx_approvals_requested_by ON pending_approvals(requested_by);
