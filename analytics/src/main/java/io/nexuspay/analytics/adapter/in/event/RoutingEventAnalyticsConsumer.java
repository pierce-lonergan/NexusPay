package io.nexuspay.analytics.adapter.in.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.analytics.application.port.out.AuthRateRollupRepository;
import io.nexuspay.analytics.domain.model.AuthRateMetric;
import io.nexuspay.common.event.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    private final AuthRateRollupRepository authRateRepository;
    private final ObjectMapper objectMapper;

    public RoutingEventAnalyticsConsumer(AuthRateRollupRepository authRateRepository,
                                          ObjectMapper objectMapper) {
        this.authRateRepository = authRateRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.ROUTING_DECISIONS, groupId = Topics.ANALYTICS_CONSUMER_GROUP)
    @Transactional
    public void consume(ConsumerRecord<String, String> record) {
        try {
            Map<String, Object> payload = objectMapper.readValue(record.value(), MAP_TYPE);
            Map<String, Object> data = payload.containsKey("payload")
                    ? extractNestedMap(payload, "payload") : payload;

            String tenantId = extractString(payload, "metadata", "tenant_id");
            if (tenantId == null) tenantId = "default";

            String psp = getString(data, "selected_psp");
            if (psp == null) psp = getString(data, "psp_connector");
            if (psp == null) return;

            Integer latency = getInteger(data, "decision_latency_ms");
            if (latency == null) return;

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
        } catch (Exception e) {
            LOG.error("Failed to process routing event for analytics: {}", e.getMessage(), e);
            throw new RuntimeException("Analytics routing consumer processing failed", e);
        }
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
