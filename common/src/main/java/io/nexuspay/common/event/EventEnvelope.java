package io.nexuspay.common.event;

import java.time.Instant;
import java.util.Map;

/**
 * Standard event envelope for all NexusPay domain events published to Kafka.
 * All modules use this structure for consistent event serialization.
 *
 * <pre>
 * {
 *   "event_id": "evt_abc123",
 *   "event_type": "PaymentCaptured",
 *   "aggregate_type": "Payment",
 *   "aggregate_id": "pi_xyz789",
 *   "timestamp": "2026-03-15T12:00:00Z",
 *   "version": 1,
 *   "metadata": { "trace_id": "...", "tenant_id": "..." },
 *   "payload": { ... }
 * }
 * </pre>
 */
public record EventEnvelope(
        String event_id,
        String event_type,
        String aggregate_type,
        String aggregate_id,
        Instant timestamp,
        int version,
        Map<String, String> metadata,
        Map<String, Object> payload
) {
    public static EventEnvelope create(String eventId, String eventType,
                                        String aggregateType, String aggregateId,
                                        Map<String, String> metadata,
                                        Map<String, Object> payload) {
        return new EventEnvelope(
                eventId, eventType, aggregateType, aggregateId,
                Instant.now(), 1, metadata, payload
        );
    }
}
