package io.nexuspay.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.analytics.adapter.in.event.PaymentEventAnalyticsConsumer;
import io.nexuspay.analytics.adapter.in.event.RoutingEventAnalyticsConsumer;
import io.nexuspay.analytics.application.port.out.AuthRateRollupRepository;
import io.nexuspay.analytics.application.port.out.DeclineRollupRepository;
import io.nexuspay.analytics.application.port.out.ProcessedEventRepository;
import io.nexuspay.analytics.application.port.out.RevenueRollupRepository;
import io.nexuspay.analytics.domain.model.AuthRateMetric;
import io.nexuspay.analytics.domain.model.DeclineAnalysis;
import io.nexuspay.analytics.domain.model.RevenueMetric;
import io.nexuspay.app.config.TestSecurityConfig;
import io.nexuspay.common.event.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * SEC-18 GATE: analytics rollups are idempotent. The additive upserts (auth_rate_hourly,
 * revenue_hourly, decline_daily) apply {@code += EXCLUDED}, so a Kafka redelivery / DLT replay of
 * the SAME event would DOUBLE-COUNT. The V4033 {@code processed_analytics_events} dedup table +
 * native {@code INSERT ... ON CONFLICT DO NOTHING} dup-no-op (per {@code (event_id, rollup_kind)})
 * makes a redelivery a no-op while leaving first-delivery and distinct-event behavior unchanged.
 *
 * <p>Mirrors {@code LedgerDoublePostRedeliveryRedteamTest}: invokes the consumer's {@code consume}
 * DIRECTLY (deterministic — no broker timing), then reads back the rollup repositories. Tests 1/3/4/5
 * FAIL on the vulnerable (no-dedup) code; test 2 (distinct events still count) passes on both,
 * distinguishing a regression from an over-fix.</p>
 */
@Import(TestSecurityConfig.class)
@DisplayName("SEC-18 GATE: analytics rollups count each event exactly once on redelivery")
class AnalyticsRollupIdempotencyIT extends IntegrationTestBase {

    @Autowired
    private PaymentEventAnalyticsConsumer paymentConsumer;

    @Autowired
    private RoutingEventAnalyticsConsumer routingConsumer;

    @Autowired
    private RevenueRollupRepository revenueRepository;

    @Autowired
    private AuthRateRollupRepository authRateRepository;

    @Autowired
    private DeclineRollupRepository declineRepository;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private ObjectMapper objectMapper;

    /** Mirrors RoutingEventAnalyticsConsumer.KIND_AUTH_RATE_HOURLY_ROUTING (private there). */
    private static final String KIND_AUTH_RATE_HOURLY_ROUTING = "AUTH_RATE_HOURLY_ROUTING";

    private static final String TENANT = "default";

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — SEC-18 analytics idempotency IT self-skips (Testcontainers required)");
    }

    // --- Envelope / record builders ---

    private ConsumerRecord<String, String> record(String topic, String eventType, String eventId,
                                                   Map<String, Object> payload) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("event_id", eventId);
        envelope.put("event_type", eventType);
        envelope.put("aggregate_type", "Payment");
        envelope.put("aggregate_id", "pi_" + eventId);
        envelope.put("metadata", Map.of("tenant_id", TENANT));
        envelope.put("payload", payload);
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(topic, 0, 0L, eventId, json);
        rec.headers().add(new RecordHeader("event_type", eventType.getBytes(StandardCharsets.UTF_8)));
        return rec;
    }

    /**
     * Builds an envelope with NO {@code event_id} (the fallback-key path) but a FIXED
     * {@code aggregate_id} and {@code timestamp}, so two such records represent the SAME logical
     * event redelivered. The consumer's {@code stableEventId} fallback must derive an identical key
     * for both (from aggregate_id + event_type + the event-borne timestamp) — never from consume-time
     * wall clock — so a redelivery dedups.
     */
    private ConsumerRecord<String, String> recordNoEventId(String topic, String eventType,
                                                           String aggregateId, String timestamp,
                                                           Map<String, Object> payload) {
        Map<String, Object> envelope = new HashMap<>();
        // event_id intentionally absent → exercises the deterministic fallback key.
        envelope.put("event_type", eventType);
        envelope.put("aggregate_type", "Payment");
        envelope.put("aggregate_id", aggregateId);
        envelope.put("timestamp", timestamp);
        envelope.put("metadata", Map.of("tenant_id", TENANT));
        envelope.put("payload", payload);
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(topic, 0, 0L, aggregateId, json);
        rec.headers().add(new RecordHeader("event_type", eventType.getBytes(StandardCharsets.UTF_8)));
        return rec;
    }

    private Map<String, Object> capturePayload(String psp, long amount) {
        Map<String, Object> p = new HashMap<>();
        p.put("amount", amount);
        p.put("currency", "USD");
        p.put("psp_connector", psp);
        p.put("payment_method", "card");
        return p;
    }

    private Map<String, Object> failedPayload(String psp, String declineCode, long amount) {
        Map<String, Object> p = new HashMap<>();
        p.put("amount", amount);
        p.put("currency", "USD");
        p.put("psp_connector", psp);
        p.put("payment_method", "card");
        p.put("decline_code", declineCode);
        p.put("card_brand", "visa");
        return p;
    }

    private Map<String, Object> routingPayload(String psp, int latencyMs) {
        Map<String, Object> p = new HashMap<>();
        p.put("selected_psp", psp);
        p.put("decision_latency_ms", latencyMs);
        p.put("currency", "USD");
        return p;
    }

    // --- Read-back helpers (wide windows; PSP filter narrows to this test's row) ---

    private RevenueMetric revenueFor(String psp) {
        Instant from = Instant.now().minusSeconds(7200);
        Instant to = Instant.now().plusSeconds(7200);
        return revenueRepository.findHourly(TENANT, from, to, psp, "USD").stream()
                .filter(m -> psp.equals(m.pspConnector()))
                .reduce((a, b) -> b) // latest matching bucket
                .orElse(null);
    }

    private AuthRateMetric authRateFor(String psp) {
        Instant from = Instant.now().minusSeconds(7200);
        Instant to = Instant.now().plusSeconds(7200);
        return authRateRepository.findHourly(TENANT, from, to, psp, null, null).stream()
                .filter(m -> psp.equals(m.pspConnector()))
                .reduce((a, b) -> b)
                .orElse(null);
    }

    private DeclineAnalysis declineFor(String psp, String declineCode) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return declineRepository.findDaily(TENANT, today.minusDays(1), today.plusDays(1),
                        psp, declineCode, null).stream()
                .filter(d -> psp.equals(d.pspConnector()) && declineCode.equals(d.declineCode()))
                .reduce((a, b) -> b)
                .orElse(null);
    }

    // --- Tests ---

    @Test
    @DisplayName("replayed capture does not double-count revenue (FAILS on vulnerable code)")
    void replayedCapture_doesNotDoubleCountRevenue() {
        String psp = "psp_cap_" + UUID.randomUUID().toString().substring(0, 8);
        String eventId = "evt_" + UUID.randomUUID();

        paymentConsumer.consume(record(Topics.PAYMENTS, "PaymentCaptured", eventId,
                capturePayload(psp, 10000L)));
        // The redelivery must be a SILENT no-op.
        assertThatCode(() -> paymentConsumer.consume(record(Topics.PAYMENTS, "PaymentCaptured", eventId,
                capturePayload(psp, 10000L))))
                .as("a redelivered capture must not throw -> no DLT")
                .doesNotThrowAnyException();

        RevenueMetric rev = revenueFor(psp);
        assertThat(rev).as("revenue row must exist after first delivery").isNotNull();
        assertThat(rev.totalVolume())
                .as("redelivery must NOT double-count total_volume")
                .isEqualByComparingTo(BigDecimal.valueOf(10000L));
        assertThat(rev.totalCount())
                .as("redelivery must NOT double-count total_count")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("distinct captures still increment (guards against over-dedup; passes on both)")
    void distinctCaptures_stillIncrement() {
        String psp = "psp_dist_" + UUID.randomUUID().toString().substring(0, 8);

        paymentConsumer.consume(record(Topics.PAYMENTS, "PaymentCaptured", "evt_" + UUID.randomUUID(),
                capturePayload(psp, 10000L)));
        paymentConsumer.consume(record(Topics.PAYMENTS, "PaymentCaptured", "evt_" + UUID.randomUUID(),
                capturePayload(psp, 10000L)));

        RevenueMetric rev = revenueFor(psp);
        assertThat(rev).isNotNull();
        assertThat(rev.totalVolume())
                .as("two DISTINCT events must both count")
                .isEqualByComparingTo(BigDecimal.valueOf(20000L));
        assertThat(rev.totalCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("replayed PaymentFailed dedups BOTH auth-rate and decline (FAILS on vulnerable code)")
    void replayedFailed_dedupsBothAuthAndDecline() {
        String psp = "psp_fail_" + UUID.randomUUID().toString().substring(0, 8);
        String declineCode = "insufficient_funds";
        String eventId = "evt_" + UUID.randomUUID();

        paymentConsumer.consume(record(Topics.PAYMENTS, "PaymentFailed", eventId,
                failedPayload(psp, declineCode, 5000L)));
        paymentConsumer.consume(record(Topics.PAYMENTS, "PaymentFailed", eventId,
                failedPayload(psp, declineCode, 5000L)));

        AuthRateMetric auth = authRateFor(psp);
        assertThat(auth).as("auth-rate row must exist after first delivery").isNotNull();
        assertThat(auth.totalDeclined() + auth.totalErrors())
                .as("redelivered PaymentFailed must count the decline/error exactly once on auth_rate")
                .isEqualTo(1);

        DeclineAnalysis decline = declineFor(psp, declineCode);
        assertThat(decline).as("decline row must exist after first delivery").isNotNull();
        assertThat(decline.totalCount())
                .as("redelivered PaymentFailed must count the decline exactly once on decline_daily")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("replayed routing decision is deduped via its own rollup_kind marker (FAILS on vulnerable code)")
    void replayedRoutingDecision_dedupedViaMarker() {
        String psp = "psp_route_" + UUID.randomUUID().toString().substring(0, 8);
        String eventId = "evt_" + UUID.randomUUID();

        routingConsumer.consume(record(Topics.ROUTING_DECISIONS, "RoutingDecisionMade", eventId,
                routingPayload(psp, 42)));
        assertThatCode(() -> routingConsumer.consume(record(Topics.ROUTING_DECISIONS,
                "RoutingDecisionMade", eventId, routingPayload(psp, 42))))
                .as("a redelivered routing decision must not throw")
                .doesNotThrowAnyException();

        AuthRateMetric auth = authRateFor(psp);
        assertThat(auth).as("auth-rate row must exist after routing enrichment").isNotNull();
        assertThat(auth.avgLatencyMs())
                .as("routing latency enrichment applied")
                .isEqualTo(42);

        // NON-VACUOUS regression detector: the routing upsert writes only a non-additive latency
        // (COALESCE), so re-applying the SAME 42 twice is indistinguishable from once on the rollup
        // itself. Instead assert directly on the dedup MARKER under its routing-specific rollup_kind:
        // exactly ONE (eventId, AUTH_RATE_HOURLY_ROUTING) row after two deliveries. On the vulnerable
        // (no-dedup) code the consumer never calls markProcessed, so this count is 0 — the test fails.
        // The UNIQUE caps a correctly-deduped pair at 1.
        assertThat(processedEvents.countMarkers(eventId, KIND_AUTH_RATE_HOURLY_ROUTING))
                .as("routing redelivery must leave exactly one dedup marker for its own rollup_kind "
                        + "(0 on no-dedup code, capped at 1 by the UNIQUE)")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("CONCURRENT redelivery of the same capture applies exactly once (saveAndFlush backstop)")
    void concurrentRedelivery_appliesOnce() throws Exception {
        String psp = "psp_conc_" + UUID.randomUUID().toString().substring(0, 8);
        String eventId = "evt_" + UUID.randomUUID();

        int copies = 8;
        ExecutorService pool = Executors.newFixedThreadPool(copies);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(copies);
        try {
            for (int i = 0; i < copies; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        paymentConsumer.consume(record(Topics.PAYMENTS, "PaymentCaptured", eventId,
                                capturePayload(psp, 10000L)));
                    } catch (Exception ignored) {
                        // A losing writer may throw (constraint/serialization); the invariant is the
                        // FINAL counter, asserted below.
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        RevenueMetric rev = revenueFor(psp);
        assertThat(rev).isNotNull();
        assertThat(rev.totalCount())
                .as("concurrent redeliveries must collapse to exactly one application")
                .isEqualTo(1);
        assertThat(rev.totalVolume()).isEqualByComparingTo(BigDecimal.valueOf(10000L));
    }

    @Test
    @DisplayName("no-event_id redelivery dedups via a consume-time-INDEPENDENT fallback key")
    void noEventIdRedelivery_dedupsViaDeterministicFallbackKey() {
        // SEC-18 fallback path: an event with NO envelope event_id must still dedup on redelivery.
        // The fallback key is aggregate_id + event_type + the EVENT-BORNE timestamp — NEVER
        // consume-time Instant.now(). The defect folded truncateToHour(Instant.now()) into the key,
        // so a redelivery that crossed an hour boundary minted a DIFFERENT key and double-counted.
        // Here both deliveries carry the SAME aggregate_id + timestamp, so the fixed code derives an
        // identical key and counts the capture exactly once.
        String psp = "psp_noid_" + UUID.randomUUID().toString().substring(0, 8);
        String aggregateId = "pi_noid_" + UUID.randomUUID();
        String eventTs = "2026-06-16T10:59:50Z";

        paymentConsumer.consume(recordNoEventId(Topics.PAYMENTS, "PaymentCaptured", aggregateId,
                eventTs, capturePayload(psp, 7000L)));
        assertThatCode(() -> paymentConsumer.consume(recordNoEventId(Topics.PAYMENTS, "PaymentCaptured",
                aggregateId, eventTs, capturePayload(psp, 7000L))))
                .as("a no-event_id redelivery must not throw")
                .doesNotThrowAnyException();

        RevenueMetric rev = revenueFor(psp);
        assertThat(rev).as("revenue row must exist after first no-event_id delivery").isNotNull();
        assertThat(rev.totalVolume())
                .as("no-event_id redelivery must NOT double-count (deterministic fallback key)")
                .isEqualByComparingTo(BigDecimal.valueOf(7000L));
        assertThat(rev.totalCount())
                .as("no-event_id redelivery must count exactly once")
                .isEqualTo(1);
    }
}
