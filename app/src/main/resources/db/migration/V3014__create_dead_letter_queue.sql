-- Sprint 3.4: Dead letter queue management table.
-- Captures failed events from .DLT topics for automated retry and manual resolution.
-- No RLS: DLQ entries may have unknown tenant; access controlled via RBAC (admin-only).

CREATE TABLE dead_letter_queue (
    id                  BIGSERIAL PRIMARY KEY,
    original_topic      VARCHAR(255) NOT NULL,
    original_partition  INT,
    original_offset     BIGINT,
    event_key           VARCHAR(255),
    event_value         TEXT,
    event_headers       JSONB,
    error_message       VARCHAR(2000),
    exception_class     VARCHAR(500),
    stack_trace         TEXT,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count         INT NOT NULL DEFAULT 0,
    max_retries         INT NOT NULL DEFAULT 5,
    next_retry_at       TIMESTAMPTZ,
    resolved_at         TIMESTAMPTZ,
    tenant_id           VARCHAR(64) NOT NULL DEFAULT 'unknown',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ DEFAULT NOW()
);

-- Reprocessor query: find retryable entries ordered by next retry time
CREATE INDEX idx_dlq_status_retry ON dead_letter_queue(status, next_retry_at);

-- Filter by original topic
CREATE INDEX idx_dlq_topic ON dead_letter_queue(original_topic);

-- Status constraint
ALTER TABLE dead_letter_queue
    ADD CONSTRAINT chk_dlq_status
    CHECK (status IN ('PENDING', 'RETRYING', 'RESOLVED', 'DISCARDED'));
