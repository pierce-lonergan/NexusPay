package io.nexuspay.analytics.adapter.out.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.analytics.application.port.out.AnalyticsEventPublisher;
import io.nexuspay.analytics.domain.event.AnomalyDetected;
import io.nexuspay.analytics.domain.event.PspHealthDegraded;
import io.nexuspay.common.event.EventEnvelope;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox adapter for analytics domain events.
 * Writes events to the shared {@code event_outbox} table for relay to Kafka.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Component
public class KafkaAnalyticsEventPublisher implements AnalyticsEventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaAnalyticsEventPublisher.class);

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public KafkaAnalyticsEventPublisher(EntityManager entityManager, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(PspHealthDegraded event) {
        publishToOutbox("PspHealth", event.pspConnector(),
                "PspHealthDegraded", Map.of(
                        "tenantId", event.tenantId(),
                        "pspConnector", event.pspConnector(),
                        "healthScore", event.healthScore(),
                        "previousScore", event.previousScore(),
                        "reason", event.reason(),
                        "detectedAt", event.detectedAt().toString()
                ), event.tenantId());
    }

    @Override
    public void publish(AnomalyDetected event) {
        publishToOutbox("PspHealth", event.pspConnector(),
                "AnomalyDetected", Map.of(
                        "tenantId", event.tenantId(),
                        "pspConnector", event.pspConnector(),
                        "metricName", event.metricName(),
                        "currentValue", event.currentValue(),
                        "expectedValue", event.expectedValue(),
                        "stdDeviation", event.stdDeviation(),
                        "detectedAt", event.detectedAt().toString()
                ), event.tenantId());
    }

    private void publishToOutbox(String aggregateType, String aggregateId,
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
                    .setParameter("routingKey", tenantId + ":" + aggregateId)
                    .setParameter("eventVersion", 1)
                    .executeUpdate();

            LOG.debug("Analytics event written to outbox: type={}, psp={}", eventType, aggregateId);
        } catch (Exception e) {
            LOG.error("Failed to write analytics event to outbox: type={}, error={}",
                    eventType, e.getMessage(), e);
            throw new RuntimeException("Failed to publish analytics event", e);
        }
    }
}
