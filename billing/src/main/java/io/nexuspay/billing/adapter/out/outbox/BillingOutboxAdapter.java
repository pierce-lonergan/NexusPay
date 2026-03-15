package io.nexuspay.billing.adapter.out.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.billing.application.port.out.BillingOutboxPort;
import io.nexuspay.common.event.EventEnvelope;
import io.nexuspay.common.id.PrefixedId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Writes billing domain events to the shared {@code event_outbox} table.
 *
 * <p>Uses the same outbox infrastructure as the payment-orchestration module.
 * Events are picked up by the OutboxRelay and published to the
 * {@code nexuspay.billing} Kafka topic.</p>
 *
 * @since 0.2.5b (Sprint 2.5b)
 */
@Component
public class BillingOutboxAdapter implements BillingOutboxPort {

    private static final Logger log = LoggerFactory.getLogger(BillingOutboxAdapter.class);

    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper;

    public BillingOutboxAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishEvent(String aggregateType, String aggregateId,
                              String eventType, Map<String, Object> payload,
                              String tenantId) {
        try {
            // Build the event envelope
            EventEnvelope envelope = new EventEnvelope(
                    PrefixedId.generate("evt_"),
                    eventType,
                    aggregateType,
                    aggregateId,
                    Instant.now(),
                    1,
                    Map.of("tenant_id", tenantId != null ? tenantId : "default"),
                    payload
            );

            String jsonPayload = objectMapper.writeValueAsString(envelope);

            // Insert directly into event_outbox via native query
            // (avoids importing OutboxEvent JPA entity from payment-orchestration module)
            entityManager.createNativeQuery(
                    "INSERT INTO event_outbox (aggregate_type, aggregate_id, event_type, payload, " +
                    "created_at, tenant_id, routing_key, event_version) " +
                    "VALUES (:aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb), " +
                    "NOW(), :tenantId, :routingKey, :eventVersion)")
                    .setParameter("aggregateType", aggregateType)
                    .setParameter("aggregateId", aggregateId)
                    .setParameter("eventType", eventType)
                    .setParameter("payload", jsonPayload)
                    .setParameter("tenantId", tenantId != null ? tenantId : "default")
                    .setParameter("routingKey", aggregateType.toLowerCase())
                    .setParameter("eventVersion", 1)
                    .executeUpdate();

            log.debug("Billing outbox event written: type={}, aggregate={}:{}",
                    eventType, aggregateType, aggregateId);

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize billing event payload: {}", e.getMessage(), e);
            throw new RuntimeException("Event serialization failed", e);
        }
    }
}
