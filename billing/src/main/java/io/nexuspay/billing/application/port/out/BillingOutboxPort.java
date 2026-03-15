package io.nexuspay.billing.application.port.out;

import java.util.Map;

/**
 * Output port for publishing billing domain events to the transactional outbox.
 *
 * <p>Events are written to the {@code event_outbox} table in the same
 * database transaction as the business state change, guaranteeing
 * at-least-once delivery to Kafka via the outbox relay.</p>
 *
 * @since 0.2.5b (Sprint 2.5b)
 */
public interface BillingOutboxPort {

    /**
     * Publishes a billing domain event to the outbox.
     *
     * @param aggregateType e.g., "Subscription", "Invoice"
     * @param aggregateId   the aggregate's prefixed ID
     * @param eventType     e.g., "SubscriptionCreated", "InvoicePaid"
     * @param payload       event payload (serialized to JSON)
     * @param tenantId      tenant context
     */
    void publishEvent(String aggregateType, String aggregateId,
                       String eventType, Map<String, Object> payload,
                       String tenantId);
}
