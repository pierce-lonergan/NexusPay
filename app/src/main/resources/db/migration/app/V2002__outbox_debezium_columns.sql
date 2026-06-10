-- ============================================================================
-- V2002: Add routing_key column to event_outbox for Debezium outbox routing
--
-- Sprint 2.2 — Event Infrastructure Upgrade
--
-- The Debezium outbox event router uses 'routing_key' to determine the target
-- Kafka topic. The value maps to the aggregate type in lowercase, producing
-- topics like nexuspay.payment, nexuspay.refund, nexuspay.ledger.
--
-- This column is populated by the application when writing outbox events.
-- Existing rows are backfilled from aggregate_type.
-- ============================================================================

-- Add routing_key column
ALTER TABLE event_outbox ADD COLUMN IF NOT EXISTS routing_key VARCHAR(64);

-- Backfill from aggregate_type (lowercase for topic naming convention)
UPDATE event_outbox
SET routing_key = LOWER(aggregate_type)
WHERE routing_key IS NULL;

-- Set default for future inserts
ALTER TABLE event_outbox ALTER COLUMN routing_key SET DEFAULT 'unknown';
ALTER TABLE event_outbox ALTER COLUMN routing_key SET NOT NULL;

-- Add version field for event versioning strategy
ALTER TABLE event_outbox ADD COLUMN IF NOT EXISTS event_version INTEGER NOT NULL DEFAULT 1;

COMMENT ON COLUMN event_outbox.routing_key IS
    'Debezium outbox router uses this to determine the target Kafka topic. '
    'Value maps to lowercase aggregate type (e.g., payment, refund, ledger).';

COMMENT ON COLUMN event_outbox.event_version IS
    'Schema version of the event payload. Used by event upcasters to transform '
    'older event formats to current format.';
