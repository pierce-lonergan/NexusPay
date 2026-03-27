package io.nexuspay.analytics.adapter.in.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.analytics.application.port.out.AuthRateRollupRepository;
import io.nexuspay.analytics.application.port.out.DeclineRollupRepository;
import io.nexuspay.analytics.application.port.out.RevenueRollupRepository;
import io.nexuspay.analytics.application.service.DeclineAnalyticsService;
import io.nexuspay.analytics.domain.model.AuthRateMetric;
import io.nexuspay.analytics.domain.model.DeclineAnalysis;
import io.nexuspay.analytics.domain.model.RevenueMetric;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Kafka consumer that processes payment lifecycle events and aggregates them
 * into analytics rollup tables (auth rates, revenue, declines).
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Component
public class PaymentEventAnalyticsConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentEventAnalyticsConsumer.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AuthRateRollupRepository authRateRepository;
    private final RevenueRollupRepository revenueRepository;
    private final DeclineRollupRepository declineRepository;
    private final ObjectMapper objectMapper;

    public PaymentEventAnalyticsConsumer(AuthRateRollupRepository authRateRepository,
                                          RevenueRollupRepository revenueRepository,
                                          DeclineRollupRepository declineRepository,
                                          ObjectMapper objectMapper) {
        this.authRateRepository = authRateRepository;
        this.revenueRepository = revenueRepository;
        this.declineRepository = declineRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.PAYMENTS, groupId = Topics.ANALYTICS_CONSUMER_GROUP)
    @Transactional
    public void consume(ConsumerRecord<String, String> record) {
        try {
            String eventType = extractHeader(record, "event_type");
            Map<String, Object> payload = objectMapper.readValue(record.value(), MAP_TYPE);

            // Extract nested payload if envelope format
            Map<String, Object> data = payload.containsKey("payload")
                    ? extractNestedMap(payload, "payload") : payload;

            String tenantId = extractString(payload, "metadata", "tenant_id");
            if (tenantId == null) tenantId = "default";

            switch (eventType) {
                case "PaymentCreated" -> handlePaymentCreated(data, tenantId);
                case "PaymentSucceeded" -> handlePaymentSucceeded(data, tenantId);
                case "PaymentFailed" -> handlePaymentFailed(data, tenantId);
                case "PaymentCaptured" -> handlePaymentCaptured(data, tenantId);
                case "RefundCompleted" -> handleRefundCompleted(data, tenantId);
                default -> LOG.trace("Ignoring event type: {}", eventType);
            }
        } catch (Exception e) {
            LOG.error("Failed to process payment event for analytics: {}", e.getMessage(), e);
            throw new RuntimeException("Analytics consumer processing failed", e);
        }
    }

    private void handlePaymentCreated(Map<String, Object> data, String tenantId) {
        Instant bucketHour = truncateToHour(Instant.now());
        String psp = getStringOrDefault(data, "psp_connector", "unknown");

        authRateRepository.upsertHourly(new AuthRateMetric(
                tenantId, bucketHour, psp,
                getString(data, "card_brand"), getString(data, "card_type"),
                getString(data, "issuing_region"), getString(data, "currency"),
                getString(data, "payment_method"),
                1, 0, 0, 0, BigDecimal.ZERO,
                null, null, null, null
        ));
    }

    private void handlePaymentSucceeded(Map<String, Object> data, String tenantId) {
        Instant bucketHour = truncateToHour(Instant.now());
        String psp = getStringOrDefault(data, "psp_connector", "unknown");
        Integer latency = getInteger(data, "latency_ms");

        authRateRepository.upsertHourly(new AuthRateMetric(
                tenantId, bucketHour, psp,
                getString(data, "card_brand"), getString(data, "card_type"),
                getString(data, "issuing_region"), getString(data, "currency"),
                getString(data, "payment_method"),
                0, 1, 0, 0, BigDecimal.ZERO,
                latency, null, latency, null
        ));
    }

    private void handlePaymentFailed(Map<String, Object> data, String tenantId) {
        Instant bucketHour = truncateToHour(Instant.now());
        String psp = getStringOrDefault(data, "psp_connector", "unknown");
        String declineCode = getString(data, "decline_code");
        String category = DeclineAnalyticsService.categorizeDecline(declineCode);
        boolean isError = "ERROR".equals(category);

        // Update auth rate rollup
        authRateRepository.upsertHourly(new AuthRateMetric(
                tenantId, bucketHour, psp,
                getString(data, "card_brand"), getString(data, "card_type"),
                getString(data, "issuing_region"), getString(data, "currency"),
                getString(data, "payment_method"),
                0, 0, isError ? 0 : 1, isError ? 1 : 0, BigDecimal.ZERO,
                null, null, null, null
        ));

        // Update decline rollup
        if (declineCode != null) {
            BigDecimal amount = getBigDecimal(data, "amount");
            declineRepository.upsertDaily(new DeclineAnalysis(
                    tenantId, LocalDate.now(ZoneOffset.UTC), psp,
                    declineCode, category,
                    getString(data, "card_brand"), getString(data, "issuing_region"),
                    getString(data, "issuer_name"),
                    1, amount != null ? amount : BigDecimal.ZERO
            ));
        }
    }

    private void handlePaymentCaptured(Map<String, Object> data, String tenantId) {
        Instant bucketHour = truncateToHour(Instant.now());
        BigDecimal amount = getBigDecimal(data, "amount");
        if (amount == null) amount = BigDecimal.ZERO;

        revenueRepository.upsertHourly(new RevenueMetric(
                tenantId, bucketHour,
                getString(data, "psp_connector"),
                getStringOrDefault(data, "currency", "USD"),
                getString(data, "payment_method"),
                amount, 1, BigDecimal.ZERO, amount,
                BigDecimal.ZERO, 0, BigDecimal.ZERO, 0
        ));
    }

    private void handleRefundCompleted(Map<String, Object> data, String tenantId) {
        Instant bucketHour = truncateToHour(Instant.now());
        BigDecimal amount = getBigDecimal(data, "amount");
        if (amount == null) amount = BigDecimal.ZERO;

        revenueRepository.upsertHourly(new RevenueMetric(
                tenantId, bucketHour,
                getString(data, "psp_connector"),
                getStringOrDefault(data, "currency", "USD"),
                getString(data, "payment_method"),
                BigDecimal.ZERO, 0, BigDecimal.ZERO, amount.negate(),
                amount, 1, BigDecimal.ZERO, 0
        ));
    }

    // --- Utility methods ---

    private Instant truncateToHour(Instant instant) {
        return instant.atZone(ZoneOffset.UTC)
                .withMinute(0).withSecond(0).withNano(0)
                .toInstant();
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
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(key);
            } else {
                return null;
            }
        }
        return current != null ? current.toString() : null;
    }

    private String getString(Map<String, Object> data, String key) {
        Object val = data.get(key);
        return val != null ? val.toString() : null;
    }

    private String getStringOrDefault(Map<String, Object> data, String key, String defaultVal) {
        Object val = data.get(key);
        return val != null ? val.toString() : defaultVal;
    }

    private Integer getInteger(Map<String, Object> data, String key) {
        Object val = data.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; } }
        return null;
    }

    private BigDecimal getBigDecimal(Map<String, Object> data, String key) {
        Object val = data.get(key);
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (val instanceof String s) { try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; } }
        return null;
    }
}
