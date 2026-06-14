package io.nexuspay.analytics.adapter.in.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.event.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Kafka consumer that processes fraud assessment events for analytics.
 * Currently logs fraud block rates per PSP for future analytics integration.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Component
public class FraudEventAnalyticsConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(FraudEventAnalyticsConsumer.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public FraudEventAnalyticsConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.FRAUD_ASSESSMENTS, groupId = Topics.ANALYTICS_CONSUMER_GROUP)
    // B-002: no DB today; when persistence is added, wrap the write in tenantWork.runInTenant(metadata.tenant_id, ...) (bind BEFORE the tx) — APP role, never @SystemTransactional.
    public void consume(ConsumerRecord<String, String> record) {
        try {
            String eventType = extractHeader(record, "event_type");
            Map<String, Object> payload = objectMapper.readValue(record.value(), MAP_TYPE);
            Map<String, Object> data = payload.containsKey("payload")
                    ? extractNestedMap(payload, "payload") : payload;

            String decision = getString(data, "decision");
            String psp = getString(data, "psp_connector");
            String tenantId = extractString(payload, "metadata", "tenant_id");

            if ("BLOCK".equals(decision) || "REVIEW".equals(decision)) {
                LOG.info("Fraud {} for PSP {} (tenant={}): score={}",
                        decision, psp, tenantId, data.get("risk_score"));
            }

            // Future: persist fraud analytics to dedicated rollup table
        } catch (Exception e) {
            LOG.error("Failed to process fraud event for analytics: {}", e.getMessage(), e);
            throw new RuntimeException("Analytics fraud consumer processing failed", e);
        }
    }

    private String extractHeader(ConsumerRecord<String, String> record, String key) {
        var header = record.headers().lastHeader(key);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : "";
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
}
