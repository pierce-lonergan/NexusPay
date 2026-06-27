package io.nexuspay.dispute.application.port.out;

import java.util.Map;

/**
 * TEST-2: output port for publishing dispute domain events to the transactional outbox.
 *
 * <p>Events are written to the shared {@code event_outbox} table in the SAME database transaction as
 * the dispute state change (transactional outbox), guaranteeing at-least-once delivery: the row commits
 * with the business op or not at all. The shared {@code OutboxRelay} polls {@code event_outbox} and the
 * gateway-api {@code WebhookDeliveryService} → {@code WebhookEnvelopeSerializer} pipeline translates the
 * internal type to its dotted canonical name ({@code dispute.*}) and delivers a signed webhook.</p>
 *
 * <p>Mirrors {@code BillingOutboxPort} exactly so the dispute module participates in the same outbox
 * infrastructure WITHOUT importing the payment-orchestration {@code OutboxEvent} JPA entity across the
 * Modulith boundary (the adapter uses a native INSERT, identical to billing).</p>
 *
 * @since TEST-2
 */
public interface DisputeOutboxPort {

    /**
     * Publishes a dispute domain event to the outbox.
     *
     * @param aggregateType always {@code "Dispute"} (the outbox relay routes it to the payments topic
     *                      the webhook consumer reads, via the DEFAULT_TOPIC fall-through)
     * @param aggregateId   the dispute's prefixed id ({@code dp_...})
     * @param eventType     the internal PascalCase type, e.g. {@code "DisputeCreated"}
     *                      (translated to the dotted canonical name at send time)
     * @param payload       event payload (the dispute {@code data.object}, serialized to JSON)
     * @param tenantId      the dispute's server-authoritative tenant (SEC-24) — NEVER {@code "default"}
     */
    void publishEvent(String aggregateType, String aggregateId,
                       String eventType, Map<String, Object> payload,
                       String tenantId);

    /**
     * Publishes a dispute event carrying the event's key MODE. A real chargeback is
     * {@code livemode=true} (the default of the base overload); a test-simulated dispute (the
     * {@code POST /v1/test/disputes} path) is {@code livemode=false}. The mode rides on the reserved
     * {@code __livemode} envelope-metadata key and is lifted to the delivered envelope's top-level
     * {@code livemode} field — matching how the mock-payment synthesizer marks a TEST webhook.
     */
    void publishEvent(String aggregateType, String aggregateId,
                       String eventType, Map<String, Object> payload,
                       String tenantId, boolean livemode);
}
