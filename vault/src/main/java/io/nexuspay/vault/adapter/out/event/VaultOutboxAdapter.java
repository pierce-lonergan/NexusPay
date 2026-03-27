package io.nexuspay.vault.adapter.out.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.event.EventEnvelope;
import io.nexuspay.vault.application.port.out.VaultEventPublisher;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox adapter for vault events.
 *
 * <p>Writes vault domain events to the {@code event_outbox} table using native SQL
 * to avoid cross-module JPA entity dependencies. Events are picked up by the
 * OutboxRelay and published to Kafka.</p>
 *
 * @since 0.4.0 (Sprint 4.1)
 */
@Component
public class VaultOutboxAdapter implements VaultEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(VaultOutboxAdapter.class);

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public VaultOutboxAdapter(EntityManager entityManager, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishEvent(String aggregateType, String aggregateId,
                              String eventType, Map<String, Object> payload, String tenantId) {
        try {
            String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
            EventEnvelope envelope = EventEnvelope.create(
                    eventId, eventType, aggregateType, aggregateId,
                    Map.of("tenant_id", tenantId),
                    payload
            );
            String jsonPayload = objectMapper.writeValueAsString(envelope);

            entityManager.createNativeQuery(
                    "INSERT INTO event_outbox (aggregate_type, aggregate_id, event_type, payload, " +
                    "created_at, tenant_id, routing_key, event_version) " +
                    "VALUES (:aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb), " +
                    "NOW(), :tenantId, :routingKey, :eventVersion)")
                    .setParameter("aggregateType", aggregateType)
                    .setParameter("aggregateId", aggregateId)
                    .setParameter("eventType", eventType)
                    .setParameter("payload", jsonPayload)
                    .setParameter("tenantId", tenantId)
                    .setParameter("routingKey", aggregateId)
                    .setParameter("eventVersion", 1)
                    .executeUpdate();

            log.debug("Vault event written to outbox: type={}, aggregate={}/{}",
                    eventType, aggregateType, aggregateId);
        } catch (Exception e) {
            log.error("Failed to write vault event to outbox: type={}, error={}",
                    eventType, e.getMessage(), e);
            throw new RuntimeException("Failed to publish vault event", e);
        }
    }
}
