package io.nexuspay.workflow.application.port.out;

import java.util.Map;

/**
 * Outbound port for publishing workflow builder domain events via the transactional outbox.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
public interface WorkflowEventPublisher {

    void publishEvent(String aggregateType, String aggregateId, String eventType,
                       Map<String, Object> payload, String tenantId);
}
