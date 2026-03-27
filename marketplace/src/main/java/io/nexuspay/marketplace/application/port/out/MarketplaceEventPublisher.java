package io.nexuspay.marketplace.application.port.out;

import java.util.Map;

/**
 * Outbound port for publishing marketplace domain events to the transactional outbox.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface MarketplaceEventPublisher {

    /**
     * Publishes a marketplace event to the event outbox table.
     *
     * @param aggregateType type of the aggregate (e.g., "ConnectedAccount", "SplitPayment")
     * @param aggregateId   unique ID of the aggregate
     * @param eventType     event type constant from EventTypes
     * @param payload       event-specific data
     * @param tenantId      tenant scope for the event
     */
    void publishEvent(String aggregateType, String aggregateId,
                      String eventType, Map<String, Object> payload, String tenantId);
}
