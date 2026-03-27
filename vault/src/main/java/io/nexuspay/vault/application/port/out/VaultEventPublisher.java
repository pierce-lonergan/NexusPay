package io.nexuspay.vault.application.port.out;

import java.util.Map;

/**
 * Out-port for publishing vault domain events via the transactional outbox.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public interface VaultEventPublisher {

    void publishEvent(String aggregateType, String aggregateId, String eventType,
                      Map<String, Object> payload, String tenantId);
}
