package io.nexuspay.dispute.adapter.out.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.event.EventEnvelope;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.dispute.application.port.out.DisputeOutboxPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TEST-2: writes dispute domain events to the shared {@code event_outbox} table.
 *
 * <p>Mirrors {@code BillingOutboxAdapter} EXACTLY — same column set, same {@code CAST(... AS jsonb)},
 * same routing-key shape ({@code aggregateType.toLowerCase()}), same native INSERT. Deliberately does
 * NOT import the payment-orchestration {@code OutboxEvent} JPA entity, so no forbidden Modulith module
 * edge is created (billing already establishes this pattern). The row is inserted inside the caller's
 * {@code @Transactional} dispute-lifecycle method, so it commits atomically with the state change
 * (transactional outbox); the shared {@code OutboxRelay} polls and publishes to Kafka, and the
 * gateway-api webhook pipeline delivers the canonical, signed {@code dispute.*} webhook.</p>
 *
 * @since TEST-2
 */
@Component
public class DisputeOutboxAdapter implements DisputeOutboxPort {

    private static final Logger log = LoggerFactory.getLogger(DisputeOutboxAdapter.class);

    /**
     * INT-3 reserved, server-only metadata key carrying the event's key mode (test=false / live=true).
     * The gateway-api {@code WebhookEnvelopeSerializer} lifts it to the top-level {@code livemode} field
     * and strips it from the delivered {@code data.metadata}. Disputes have no payment-webhook-metadata
     * row, so the mode rides on the ENVELOPE metadata instead. Must match
     * {@code WebhookEnvelopeSerializer.LIVEMODE_KEY}.
     */
    static final String LIVEMODE_KEY = "__livemode";

    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper;

    public DisputeOutboxAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishEvent(String aggregateType, String aggregateId,
                             String eventType, Map<String, Object> payload,
                             String tenantId) {
        // Default to a REAL (live) dispute — a real chargeback. The test-mode simulator path uses the
        // livemode overload to stamp test=false.
        publishEvent(aggregateType, aggregateId, eventType, payload, tenantId, true);
    }

    /**
     * TEST-2: publish carrying the event's key mode. A real chargeback is {@code livemode=true}; a
     * test-simulated dispute (the {@code POST /v1/test/disputes} path) is {@code livemode=false}. The
     * flag is written into the envelope metadata under the reserved {@code __livemode} key so the
     * delivery serializer lifts it to the top-level {@code livemode} field — consistent with how the
     * mock-payment synthesizer marks a TEST webhook.
     */
    @Override
    public void publishEvent(String aggregateType, String aggregateId,
                             String eventType, Map<String, Object> payload,
                             String tenantId, boolean livemode) {
        try {
            // Build the event envelope. The metadata carries the trusted tenant (mirrors billing) plus
            // the reserved server-only __livemode mode marker (lifted to top-level livemode at send time).
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("tenant_id", tenantId != null ? tenantId : "default");
            metadata.put(LIVEMODE_KEY, Boolean.toString(livemode));

            EventEnvelope envelope = new EventEnvelope(
                    PrefixedId.generate("evt_"),
                    eventType,
                    aggregateType,
                    aggregateId,
                    Instant.now(),
                    1,
                    metadata,
                    payload
            );

            String jsonPayload = objectMapper.writeValueAsString(envelope);

            // Insert directly into event_outbox via native query — SAME columns / jsonb cast / routing
            // key as BillingOutboxAdapter (avoids importing OutboxEvent from payment-orchestration).
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

            log.debug("Dispute outbox event written: type={}, aggregate={}:{}, livemode={}",
                    eventType, aggregateType, aggregateId, livemode);

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize dispute event payload: {}", e.getMessage(), e);
            throw new RuntimeException("Event serialization failed", e);
        }
    }
}
