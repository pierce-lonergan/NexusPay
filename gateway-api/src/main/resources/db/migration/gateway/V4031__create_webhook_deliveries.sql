-- INT-4: persisted at-least-once outbound webhook delivery ledger (one row per (endpoint, logical event)).
-- Records every matching event per subscribed endpoint, then attempts delivery; transient failures are
-- retried with exponential backoff + jitter by the leader-locked WebhookDeliveryRetrier; max attempts -> DEAD (DLQ).
-- NUMBERING: Flyway versions are GLOBAL across module leaf locations (see V4022/V4030 headers, app/application.yml
-- lists every leaf under one history). V4030 is the current global max, so this is V4031. Out-of-order disabled.
--
-- PCI/SEC-4: this table stores the CANONICAL OUTBOUND ENVELOPE body only -- the same bytes already shipped to the
-- merchant. WebhookEnvelopeSerializer.normalizeObject strips card/PAN subtrees (payment_method_data/card/
-- payment_method) and WebhookMetadataService.sanitize never persists PAN upstream, so canonical_body carries no
-- recoverable card data. The endpoint SECRET is NEVER stored here (it lives in webhook_endpoints and is read live
-- per attempt, so a rotated secret takes effect on the next attempt). No PAN, no secret.
CREATE TABLE IF NOT EXISTS webhook_deliveries (
    id               VARCHAR(64)  PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    endpoint_id      VARCHAR(64)  NOT NULL,
    event_id         VARCHAR(255) NOT NULL,                 -- STABLE INT-1 id (WebhookEnvelopeSerializer.stableId)
    event_type       VARCHAR(128) NOT NULL,                 -- dotted canonical type
    canonical_body   TEXT         NOT NULL,                 -- exact bytes to (re)sign+POST; rebuilt-equivalent, no PAN
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempt_count    INTEGER      NOT NULL DEFAULT 0,
    max_attempts     INTEGER      NOT NULL DEFAULT 8,
    next_attempt_at  TIMESTAMP WITH TIME ZONE,              -- NULL once DELIVERED/DEAD
    last_status_code INTEGER,
    last_error       VARCHAR(512),                          -- bounded; never the body/secret/metadata
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_webhook_delivery_status CHECK (status IN ('PENDING','DELIVERED','FAILED','DEAD')),
    CONSTRAINT fk_webhook_delivery_endpoint FOREIGN KEY (endpoint_id)
        REFERENCES webhook_endpoints (id)
);

-- IDEMPOTENT RECORDING (invariant #1): one logical delivery per (endpoint, event). A duplicate INSERT
-- (Kafka redelivery / DLT replay) hits this unique index; the recorder does saveAndFlush + dup-key no-op (L-041).
CREATE UNIQUE INDEX IF NOT EXISTS uq_webhook_deliveries_endpoint_event
    ON webhook_deliveries (endpoint_id, event_id);

-- Retrier due-scan: FAILED rows whose next_attempt_at <= now, oldest first. Partial index keeps it tight.
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_due
    ON webhook_deliveries (next_attempt_at)
    WHERE status = 'FAILED';

-- Crash-recovery sweep (INT-4 BLOCKER): STALE PENDING rows whose outcome was never written because the
-- process crashed between the PENDING commit and the outcome write. The retrier sweeps these (guarded by a
-- staleness threshold on created_at) so a pre-outcome crash cannot strand a delivery the merchant never got.
-- Partial index keeps the scan tight (only the small set of in-flight PENDING rows is ever indexed here).
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_pending_stale
    ON webhook_deliveries (created_at)
    WHERE status = 'PENDING';

-- List API: by endpoint and by event, tenant-scoped.
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_endpoint ON webhook_deliveries (tenant_id, endpoint_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_event    ON webhook_deliveries (tenant_id, event_id);

-- Tenant isolation (RLS), normalized dialect identical to V4022/V4030 (current_tenant_id() from V2001).
-- tenant_id is NOT NULL here (every delivery is for a resolved real tenant); IS NOT DISTINCT FROM still matches.
ALTER TABLE webhook_deliveries ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_webhook_deliveries ON webhook_deliveries
    FOR ALL
    USING (tenant_id IS NOT DISTINCT FROM current_tenant_id())
    WITH CHECK (tenant_id IS NOT DISTINCT FROM current_tenant_id());
