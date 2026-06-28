package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.event.EventEnvelope;
import io.nexuspay.common.id.PrefixedId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TEST-4a (D1): the synthesis sink for the test-event trigger. Writes ONE row to the shared
 * {@code event_outbox} table via a native INSERT under the CALLER's tenant, so the existing
 * {@code OutboxRelay → WebhookDeliveryService} pipeline picks it up and delivers a canonical, signed,
 * {@code livemode:false} webhook to the caller tenant's enabled endpoints — with ZERO new delivery code.
 *
 * <p>Mirrors {@code DisputeOutboxAdapter} EXACTLY — same column set, same {@code CAST(... AS jsonb)}, same
 * routing-key shape ({@code aggregateType.toLowerCase()}), same native INSERT. Deliberately does NOT import
 * the payment-orchestration {@code OutboxEvent} JPA entity, so NO forbidden Spring-Modulith module edge is
 * created (gateway-api may depend on common; it does not depend on payment-orchestration's persistence
 * entity here — the native INSERT needs only the shared table, which is in the same datasource).</p>
 *
 * <p>The mode marker rides on the ENVELOPE metadata under the reserved {@code __livemode} key (the
 * DisputeOutboxAdapter precedent for aggregates with no payment-webhook-metadata row);
 * {@code WebhookEnvelopeSerializer.envelopeLivemode} lifts it to the top-level {@code livemode} field at send
 * time. The trigger always stamps {@code __livemode=false}, so a synthesized webhook is unambiguously a TEST
 * event. The row is inserted inside the request {@code @Transactional} so it commits transactionally.</p>
 *
 * @since TEST-4a
 */
@Component
public class TestEventOutboxAdapter {

    private static final Logger log = LoggerFactory.getLogger(TestEventOutboxAdapter.class);

    /**
     * Reserved, server-only metadata key carrying the event's key mode (test=false / live=true). Must match
     * {@code WebhookEnvelopeSerializer.LIVEMODE_KEY} (and {@code DisputeOutboxAdapter.LIVEMODE_KEY}).
     */
    static final String LIVEMODE_KEY = "__livemode";

    /** Marks the event's origin so a delivered test webhook is traceable as synthesized, not a real flow. */
    static final String SOURCE_KEY = "__source";
    static final String SOURCE_VALUE = "test_trigger";

    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper;

    public TestEventOutboxAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * TEST-4a: synthesize + persist ONE {@code event_outbox} row for the test trigger.
     *
     * @param tenantId      the CALLER's tenant (from {@code CallerTenant.require()} — NEVER a body value);
     *                      the fan-out is scoped to this tenant's endpoints by WebhookDeliveryService.
     * @param internalType  the internal PascalCase event type (from {@code WebhookEventTaxonomy.fromDotted})
     * @param aggregateType the aggregate type (from {@code WebhookEventTaxonomy.aggregateTypeFor})
     * @param aggregateId   the object id (a test-prefixed id; opaque, NOT resolved against any aggregate)
     * @param object        the synthesized {@code data.object} (becomes the outbox payload / envelope payload)
     * @param livemode      always {@code false} for the test trigger (stamped on the envelope metadata)
     * @return the synthesized stable event id ({@code evt_*}) carried on the envelope
     */
    @Transactional
    public String synthesize(String tenantId, String internalType, String aggregateType,
                             String aggregateId, Map<String, Object> object, boolean livemode) {
        try {
            String eventId = PrefixedId.event();

            // Envelope metadata carries the trusted tenant (mirrors DisputeOutboxAdapter) + the reserved
            // server-only __livemode marker (lifted to top-level livemode at send time) + a source marker.
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("tenant_id", tenantId != null ? tenantId : "default");
            metadata.put(LIVEMODE_KEY, Boolean.toString(livemode));
            metadata.put(SOURCE_KEY, SOURCE_VALUE);

            EventEnvelope envelope = new EventEnvelope(
                    eventId,
                    internalType,
                    aggregateType,
                    aggregateId,
                    Instant.now(),
                    1,
                    metadata,
                    object
            );

            String jsonPayload = objectMapper.writeValueAsString(envelope);

            // Native INSERT into event_outbox — SAME columns / jsonb cast / routing key as
            // DisputeOutboxAdapter (avoids importing OutboxEvent from payment-orchestration).
            entityManager.createNativeQuery(
                    "INSERT INTO event_outbox (aggregate_type, aggregate_id, event_type, payload, " +
                    "created_at, tenant_id, routing_key, event_version) " +
                    "VALUES (:aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb), " +
                    "NOW(), :tenantId, :routingKey, :eventVersion)")
                    .setParameter("aggregateType", aggregateType)
                    .setParameter("aggregateId", aggregateId)
                    .setParameter("eventType", internalType)
                    .setParameter("payload", jsonPayload)
                    .setParameter("tenantId", tenantId != null ? tenantId : "default")
                    .setParameter("routingKey", aggregateType.toLowerCase())
                    .setParameter("eventVersion", 1)
                    .executeUpdate();

            log.info("Test event synthesized: type={}, aggregate={}:{}, tenant={}, livemode={}",
                    internalType, aggregateType, aggregateId, tenantId, livemode);

            return eventId;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize synthesized test event payload: {}", e.getMessage(), e);
            throw new RuntimeException("Test event serialization failed", e);
        }
    }
}
