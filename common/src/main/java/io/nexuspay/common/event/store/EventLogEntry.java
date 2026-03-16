package io.nexuspay.common.event.store;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable representation of a single entry in the append-only event log.
 * Captures all published events for audit trail and potential future replay.
 *
 * @param eventId        unique event identifier
 * @param aggregateType  aggregate type (e.g. "Payment", "Subscription")
 * @param aggregateId    aggregate instance identifier
 * @param eventType      event type name (e.g. "PaymentCaptured")
 * @param eventVersion   schema version of the event
 * @param payload        serialized event payload (JSON or Avro bytes)
 * @param payloadFormat  serialization format: JSON or AVRO
 * @param metadata       event metadata (trace_id, tenant_id, etc.)
 * @param tenantId       tenant identifier for RLS
 * @param createdAt      timestamp of log entry creation
 */
public record EventLogEntry(
        String eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        int eventVersion,
        byte[] payload,
        PayloadFormat payloadFormat,
        Map<String, String> metadata,
        String tenantId,
        Instant createdAt
) {
    public enum PayloadFormat {
        JSON, AVRO
    }
}
