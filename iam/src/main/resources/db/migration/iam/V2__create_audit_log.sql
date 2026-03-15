-- Audit log: tracks all significant actions for compliance
CREATE TABLE audit_log (
    id              VARCHAR(64) PRIMARY KEY,
    actor           VARCHAR(128) NOT NULL,
    action          VARCHAR(64) NOT NULL,
    resource_type   VARCHAR(64),
    resource_id     VARCHAR(64),
    details         JSONB,
    ip_address      VARCHAR(45),
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'default',
    timestamp       TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_actor ON audit_log(actor);
CREATE INDEX idx_audit_log_action ON audit_log(action);
CREATE INDEX idx_audit_log_resource ON audit_log(resource_type, resource_id);
CREATE INDEX idx_audit_log_timestamp ON audit_log(timestamp);
CREATE INDEX idx_audit_log_tenant ON audit_log(tenant_id);
