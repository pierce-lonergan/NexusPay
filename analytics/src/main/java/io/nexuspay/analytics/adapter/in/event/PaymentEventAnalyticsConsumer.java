package io.nexuspay.analytics.adapter.in.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.analytics.application.port.out.AuthRateRollupRepository;
import io.nexuspay.analytics.application.port.out.DeclineRollupRepository;
import io.nexuspay.analytics.application.port.out.ProcessedEventRepository;
import io.nexuspay.analytics.application.port.out.RevenueRollupRepository;
import io.nexuspay.analytics.application.service.DeclineAnalyticsService;
import io.nexuspay.analytics.domain.model.AuthRateMetric;
import io.nexuspay.analytics.domain.model.DeclineAnalysis;
import io.nexuspay.analytics.domain.model.RevenueMetric;
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

    // SEC-18: per-upsert-target rollup_kind discriminators. The dedup is per (event_id, rollup_kind)
    // so one event that legitimately updates SEVERAL distinct rollups (e.g. PaymentFailed → auth_rate
    // AND decline) still updates each exactly once, and a redelivery updates none.
    private static final String KIND_AUTH_RATE_HOURLY = "AUTH_RATE_HOURLY";
    private static final String KIND_DECLINE_DAILY = "DECLINE_DAILY";
    private static final String KIND_REVENUE_HOURLY = "REVENUE_HOURLY";

    private final AuthRateRollupRepository authRateRepository;
    private final RevenueRollupRepository revenueRepository;
    private final DeclineRollupRepository declineRepository;
    private final ProcessedEventRepository processedEvents;
    private final ObjectMapper objectMapper;
    private final TenantWorkRunner tenantWork;

    public PaymentEventAnalyticsConsumer(AuthRateRollupRepository authRateRepository,
                                          RevenueRollupRepository revenueRepository,
                                          DeclineRollupRepository declineRepository,
                                          ProcessedEventRepository processedEvents,
                                          ObjectMapper objectMapper,
                                          TenantWorkRunner tenantWork) {
        this.authRateRepository = authRateRepository;
        this.revenueRepository = revenueRepository;
        this.declineRepository = declineRepository;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
        this.tenantWork = tenantWork;
    }

    @KafkaListener(topics = Topics.PAYMENTS, groupId = Topics.ANALYTICS_CONSUMER_GROUP)
    public void consume(ConsumerRecord<String, String> record) {
        try {
            String eventType = extractHeader(record, "event_type");
            Map<String, Object> payload = objectMapper.readValue(record.value(), MAP_TYPE);

            // Extract nested payload if envelope format
            Map<String, Object> data = payload.containsKey("payload")
                    ? extractNestedMap(payload, "payload") : payload;

            String tenantId = extractString(payload, "metadata", "tenant_id");
            if (tenantId == null || tenantId.isBlank()) tenantId = "default";

            // SEC-18: the stable logical event id (envelope-level event_id, with a deterministic
            // fallback) keys the dedup marker. Extracted ONCE here from the top-level envelope map.
            final String eventId = stableEventId(payload, data, eventType, record);

            // B-002: bind the tenant BEFORE the transaction begins. tenantWork opens a REQUIRES_NEW
            // transaction bound to this tenant on the RLS APP role, so the rollup upserts run inside
            // ONE tenant-scoped transaction (RLS WITH CHECK guards each write). The SEC-18 dedup
            // marker insert (saveAndFlush) is atomic with the additive upsert(s) in this same tx.
            // Dormant at enforce=false.
            final String tenant = tenantId;
            tenantWork.runInTenant(tenant, () -> doConsume(eventType, data, tenant, eventId));
        } catch (Exception e) {
            LOG.error("Failed to process payment event for analytics: {}", e.getMessage(), e);
            throw new RuntimeException("Analytics consumer processing failed", e);
        }
    }

    private void doConsume(String eventType, Map<String, Object> data, String tenantId, String eventId) {
        switch (eventType) {
            case "PaymentCreated" -> handlePaymentCreated(data, tenantId, eventId);
            case "PaymentSucceeded" -> handlePaymentSucceeded(data, tenantId, eventId);
            case "PaymentFailed" -> handlePaymentFailed(data, tenantId, eventId);
            case "PaymentCaptured" -> handlePaymentCaptured(data, tenantId, eventId);
            case "RefundCompleted" -> handleRefundCompleted(data, tenantId, eventId);
            default -> LOG.trace("Ignoring event type: {}", eventType);
        }
    }

    private void handlePaymentCreated(Map<String, Object> data, String tenantId, String eventId) {
        // SEC-18: dedup the auth-rate upsert; skip on redelivery so the counter is not double-counted.
        if (!processedEvents.markProcessed(eventId, KIND_AUTH_RATE_HOURLY, tenantId)) {
            return;
        }
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

    private void handlePaymentSucceeded(Map<String, Object> data, String tenantId, String eventId) {
        // SEC-18: dedup the auth-rate upsert; skip on redelivery so the counter is not double-counted.
        if (!processedEvents.markProcessed(eventId, KIND_AUTH_RATE_HOURLY, tenantId)) {
            return;
        }
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

    private void handlePaymentFailed(Map<String, Object> data, String tenantId, String eventId) {
        Instant bucketHour = truncateToHour(Instant.now());
        String psp = getStringOrDefault(data, "psp_connector", "unknown");
        String declineCode = getString(data, "decline_code");
        String category = DeclineAnalyticsService.categorizeDecline(declineCode);
        boolean isError = "ERROR".equals(category);

        // Update auth rate rollup — SEC-18: dedup per (event_id, AUTH_RATE_HOURLY).
        if (processedEvents.markProcessed(eventId, KIND_AUTH_RATE_HOURLY, tenantId)) {
            authRateRepository.upsertHourly(new AuthRateMetric(
                    tenantId, bucketHour, psp,
                    getString(data, "card_brand"), getString(data, "card_type"),
                    getString(data, "issuing_region"), getString(data, "currency"),
                    getString(data, "payment_method"),
                    0, 0, isError ? 0 : 1, isError ? 1 : 0, BigDecimal.ZERO,
                    null, null, null, null
            ));
        }

        // Update decline rollup — SEC-18: a SEPARATE dedup key (DECLINE_DAILY) so this same
        // PaymentFailed event applies to BOTH auth_rate AND decline exactly once each, and a
        // redelivery skips both.
        if (declineCode != null
                && processedEvents.markProcessed(eventId, KIND_DECLINE_DAILY, tenantId)) {
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

    private void handlePaymentCaptured(Map<String, Object> data, String tenantId, String eventId) {
        // SEC-18: dedup the revenue upsert; skip on redelivery so total_volume is not double-counted.
        if (!processedEvents.markProcessed(eventId, KIND_REVENUE_HOURLY, tenantId)) {
            return;
        }
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

    private void handleRefundCompleted(Map<String, Object> data, String tenantId, String eventId) {
        // SEC-18: dedup the revenue upsert; skip on redelivery so refund_volume is not double-counted.
        if (!processedEvents.markProcessed(eventId, KIND_REVENUE_HOURLY, tenantId)) {
            return;
        }
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

    /**
     * SEC-18: the stable logical event id used as the dedup key. The published envelope
     * (EventEnvelope) carries a top-level {@code event_id} (set per-publish via the outbox /
     * KafkaAnalyticsEventPublisher), so a redelivery / DLT replay of the SAME logical event carries
     * the SAME {@code event_id}. Prefer it.
     *
     * <p>When {@code event_id} is absent/blank (a legacy or forged event with no envelope id),
     * derive a DETERMINISTIC fallback key from data CARRIED IN the event:
     * {@code aggregate_id + ":" + event_type + ":" + envelope.timestamp} (or {@code aggregate_id +
     * ":" + event_type} when no timestamp is present). The time component MUST come from the event's
     * own {@code timestamp}, NEVER from consume-time {@code Instant.now()}: a DLT replay / redelivery
     * that lands in a DIFFERENT wall-clock hour than the first delivery (first at 10:59:50, replay at
     * 11:00:10 — exactly the slow-replay case) would otherwise mint a DIFFERENT key and double-count.
     * Using the event-borne timestamp keeps the fallback key identical across redeliveries regardless
     * of WHEN they are consumed. Kafka coordinates (topic/partition/offset) are the last resort: they
     * shift across rebalances, so they are the weakest signal and used only when even the aggregate
     * fallback is empty.</p>
     */
    private String stableEventId(Map<String, Object> envelope, Map<String, Object> data,
                                 String eventType, ConsumerRecord<String, String> record) {
        String envelopeId = getString(envelope, "event_id");
        if (envelopeId != null && !envelopeId.isBlank()) {
            return envelopeId;
        }
        String aggregateId = getString(envelope, "aggregate_id");
        if (aggregateId == null || aggregateId.isBlank()) {
            aggregateId = getString(data, "payment_id");
        }
        if (aggregateId != null && !aggregateId.isBlank()) {
            // Event-borne timestamp ONLY — never consume-time wall clock (would break dedup across an
            // hour-boundary redelivery). Omit the time component entirely when the event carries none.
            String eventTs = getString(envelope, "timestamp");
            return (eventTs != null && !eventTs.isBlank())
                    ? aggregateId + ":" + eventType + ":" + eventTs
                    : aggregateId + ":" + eventType;
        }
        // Last resort: the physical Kafka coordinates.
        return record.topic() + "-" + record.partition() + "-" + record.offset();
    }

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
