-- Sprint 4.4: Visual Workflow Builder tables
-- V4004 — Workflow definitions, nodes, edges, versions, triggers, executions

-- Workflow definitions
CREATE TABLE IF NOT EXISTS workflow_definitions (
    id              VARCHAR(64)  PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    version         INTEGER      NOT NULL DEFAULT 1,
    trigger_type    VARCHAR(16)  NOT NULL,
    trigger_config  JSONB,
    nodes           JSONB        NOT NULL DEFAULT '[]'::jsonb,
    edges           JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(128)
);

CREATE INDEX idx_workflow_definitions_tenant_id ON workflow_definitions(tenant_id);
CREATE INDEX idx_workflow_definitions_status ON workflow_definitions(status);

-- Workflow versions (immutable snapshots)
CREATE TABLE IF NOT EXISTS workflow_versions (
    id                  VARCHAR(64)  PRIMARY KEY,
    workflow_id         VARCHAR(64)  NOT NULL REFERENCES workflow_definitions(id),
    version_number      INTEGER      NOT NULL,
    graph_snapshot      JSONB        NOT NULL,
    change_description  TEXT,
    published_by        VARCHAR(128),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (workflow_id, version_number)
);

CREATE INDEX idx_workflow_versions_workflow_id ON workflow_versions(workflow_id);

-- Webhook triggers
CREATE TABLE IF NOT EXISTS webhook_triggers (
    id              VARCHAR(64)  PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    workflow_id     VARCHAR(64)  NOT NULL REFERENCES workflow_definitions(id),
    url_path        VARCHAR(255) NOT NULL UNIQUE,
    secret          VARCHAR(128) NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhook_triggers_workflow_id ON webhook_triggers(workflow_id);
CREATE INDEX idx_webhook_triggers_url_path ON webhook_triggers(url_path);

-- Workflow executions
CREATE TABLE IF NOT EXISTS workflow_executions (
    id                    VARCHAR(64)  PRIMARY KEY,
    tenant_id             VARCHAR(64)  NOT NULL,
    workflow_id           VARCHAR(64)  NOT NULL REFERENCES workflow_definitions(id),
    workflow_version      INTEGER      NOT NULL,
    temporal_workflow_id  VARCHAR(255),
    status                VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
    trigger_payload       JSONB,
    result_payload        JSONB,
    failure_reason        TEXT,
    current_node_id       VARCHAR(64),
    started_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at          TIMESTAMPTZ
);

CREATE INDEX idx_workflow_executions_tenant_id ON workflow_executions(tenant_id);
CREATE INDEX idx_workflow_executions_workflow_id ON workflow_executions(workflow_id);
CREATE INDEX idx_workflow_executions_status ON workflow_executions(status);

-- Row-Level Security
ALTER TABLE workflow_definitions ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_triggers ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_executions ENABLE ROW LEVEL SECURITY;

CREATE POLICY workflow_definitions_tenant_isolation ON workflow_definitions
    USING (tenant_id = current_setting('app.current_tenant_id', true));
CREATE POLICY workflow_versions_tenant_isolation ON workflow_versions
    USING (workflow_id IN (SELECT id FROM workflow_definitions WHERE tenant_id = current_setting('app.current_tenant_id', true)));
CREATE POLICY webhook_triggers_tenant_isolation ON webhook_triggers
    USING (tenant_id = current_setting('app.current_tenant_id', true));
CREATE POLICY workflow_executions_tenant_isolation ON workflow_executions
    USING (tenant_id = current_setting('app.current_tenant_id', true));

-- Grants for nexuspay_app role
GRANT SELECT, INSERT, UPDATE, DELETE ON workflow_definitions TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON workflow_versions TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON webhook_triggers TO nexuspay_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON workflow_executions TO nexuspay_app;
