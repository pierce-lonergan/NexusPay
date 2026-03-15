package io.nexuspay.payment.domain.event;

import java.time.Instant;
import java.util.Map;

/**
 * Base event envelope for all payment domain events.
 * Follows the standard NexusPay event envelope structure.
 *
 * These events are written to the event_outbox table and relayed to Kafka
 * by the outbox polling relay.
 */
public record PaymentEvent(
        String eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        Instant timestamp,
        int version,
        Map<String, String> metadata,
        Map<String, Object> payload
) {
    public static final String AGGREGATE_TYPE = "Payment";

    // Event types
    public static final String PAYMENT_CREATED = "PaymentCreated";
    public static final String PAYMENT_AUTHORIZED = "PaymentAuthorized";
    public static final String PAYMENT_CAPTURED = "PaymentCaptured";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String PAYMENT_VOIDED = "PaymentVoided";
    public static final String REFUND_CREATED = "RefundCreated";
    public static final String REFUND_COMPLETED = "RefundCompleted";
    public static final String REFUND_FAILED = "RefundFailed";
}
