package io.nexuspay.b2b.application.port.out;

import java.util.Map;

/**
 * Outbound port for publishing B2B domain events to the transactional outbox.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public interface B2bEventPublisher {

    void publishEvent(String aggregateType, String aggregateId,
                      String eventType, Map<String, Object> payload, String tenantId);
}
