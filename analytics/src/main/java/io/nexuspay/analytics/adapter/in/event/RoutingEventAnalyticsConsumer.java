package io.nexuspay.analytics.adapter.in.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.analytics.application.port.out.AuthRateRollupRepository;
import io.nexuspay.analytics.application.port.out.ProcessedEventRepository;
import io.nexuspay.analytics.domain.model.AuthRateMetric;
import io.nexuspay.common.event.Topics;
import io.nexuspay.common.rls.TenantWorkRunner;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Kafka consumer that processes routing decision events to enrich
 * auth rate analytics with latency data from routing decisions.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Component
public class RoutingEventAnalyticsConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingEventAnalyticsConsumer.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    // SEC-18: a DISTINCT rollup_kind from payment's AUTH_RATE_HOURLY. A routing event and a payment
    // event are different logical events with different event_ids (so different keys anyway); the
    // distinct kind documents that routing's latency enrichment is a separate logical contribution to
    // the same auth_rate_hourly table and must not be blocked by a payment event's prior application.
    private static final String KIND_AUTH_RATE_HOURLY_ROUTING = "AUTH_RATE_HOURLY_ROUTING";

    private final AuthRateRollupRepository authRateRepository;
    private final ProcessedEventRepository processedEvents;
    private final ObjectMapper objectMapper;
    private final TenantWorkRunner tenantWork;

    public RoutingEventAnalyticsConsumer(AuthRateRollupRepository authRateRepository,
                                          ProcessedEventRepository processedEvents,
                                          ObjectMapper objectMapper,
                                          TenantWorkRunner tenantWork) {
        this.authRateRepository = authRateRepository;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
        this.tenantWork = tenantWork;
    }

    @KafkaListener(topics = Topics.ROUTING_DECISIONS, groupId = Topics.ANALYTICS_CONSUMER_GROUP)
    public void consume(ConsumerRecord<String, String> record) {
        try {
            Map<String, Object> payload = objectMapper.readValue(record.value(), MAP_TYPE);
            Map<String, Object> data = payload.containsKey("payload")
                    ? extractNestedMap(payload, "payload") : payload;

            String tenantId = extractString(payload, "metadata", "tenant_id");
            if (tenantId == null || tenantId.isBlank()) tenantId = "default";

            // SEC-18: stable logical event id (envelope event_id, with a deterministic fallback) keys
            // the dedup marker so a routing-decision redelivery does not double-count latency/attempts.
            final String eventId = stableEventId(payload, data, record);

            // B-002: bind the tenant BEFORE the transaction begins. tenantWork opens a REQUIRES_NEW
            // transaction bound to this tenant on the RLS APP role, so the auth-rate upsert runs inside
            // ONE tenant-scoped transaction (RLS WITH CHECK guards the write). The SEC-18 dedup marker
            // insert (saveAndFlush) is atomic with the upsert in this same tx. Dormant at enforce=false.
            final String tenant = tenantId;
            tenantWork.runInTenant(tenant, () -> doConsume(data, tenant, eventId));
        } catch (Exception e) {
            LOG.error("Failed to process routing event for analytics: {}", e.getMessage(), e);
            throw new RuntimeException("Analytics routing consumer processing failed", e);
        }
    }

    private void doConsume(Map<String, Object> data, String tenantId, String eventId) {
        String psp = getString(data, "selected_psp");
        if (psp == null) psp = getString(data, "psp_connector");
        if (psp == null) return;

        Integer latency = getInteger(data, "decision_latency_ms");
        if (latency == null) return;

        // SEC-18: dedup the auth-rate enrichment upsert; skip on redelivery so latency/attempts are
        // not double-counted. Guarded AFTER the no-op early returns above so a marker is only claimed
        // when there is an upsert to apply.
        if (!processedEvents.markProcessed(eventId, KIND_AUTH_RATE_HOURLY_ROUTING, tenantId)) {
            return;
        }

        Instant bucketHour = Instant.now().atZone(ZoneOffset.UTC)
                .withMinute(0).withSecond(0).withNano(0).toInstant();

        // Enrich auth rate with routing latency data
        authRateRepository.upsertHourly(new AuthRateMetric(
                tenantId, bucketHour, psp,
                null, null, null, getString(data, "currency"), null,
                0, 0, 0, 0, BigDecimal.ZERO,
                latency, null, latency, null
        ));

        LOG.debug("Routing decision processed for PSP {} with latency {}ms", psp, latency);
    }

    /**
     * SEC-18: stable logical event id (envelope {@code event_id}, with a deterministic fallback to
     * {@code aggregate_id} / {@code payment_id} + the event-borne {@code timestamp}, then Kafka
     * coordinates). See {@code PaymentEventAnalyticsConsumer.stableEventId} for the rationale.
     *
     * <p>The fallback time component comes from the event's own {@code timestamp}, NEVER from
     * consume-time {@code Instant.now()}: a redelivery that lands in a different wall-clock hour
     * would otherwise mint a different key and double-count. When the event carries no timestamp,
     * the time component is omitted entirely.</p>
     */
    private String stableEventId(Map<String, Object> envelope, Map<String, Object> data,
                                 ConsumerRecord<String, String> record) {
        String envelopeId = getString(envelope, "event_id");
        if (envelopeId != null && !envelopeId.isBlank()) {
            return envelopeId;
        }
        String aggregateId = getString(envelope, "aggregate_id");
        if (aggregateId == null || aggregateId.isBlank()) {
            aggregateId = getString(data, "payment_id");
        }
        if (aggregateId != null && !aggregateId.isBlank()) {
            String eventTs = getString(envelope, "timestamp");
            return (eventTs != null && !eventTs.isBlank())
                    ? aggregateId + ":routing:" + eventTs
                    : aggregateId + ":routing";
        }
        return record.topic() + "-" + record.partition() + "-" + record.offset();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractNestedMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof Map ? (Map<String, Object>) val : map;
    }

    private String extractString(Map<String, Object> map, String... path) {
        Object current = map;
        for (String key : path) {
            if (current instanceof Map) { current = ((Map<?, ?>) current).get(key); } else { return null; }
        }
        return current != null ? current.toString() : null;
    }

    private String getString(Map<String, Object> data, String key) {
        Object val = data.get(key);
        return val != null ? val.toString() : null;
    }

    private Integer getInteger(Map<String, Object> data, String key) {
        Object val = data.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; } }
        return null;
    }
}
