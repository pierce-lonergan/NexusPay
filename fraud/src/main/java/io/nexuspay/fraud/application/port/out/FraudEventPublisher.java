package io.nexuspay.fraud.application.port.out;

import java.util.Map;

/**
 * Outbound port for publishing fraud domain events to the transactional outbox.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public interface FraudEventPublisher {

    void publishEvent(String aggregateType, String aggregateId,
                      String eventType, Map<String, Object> payload, String tenantId);
}
