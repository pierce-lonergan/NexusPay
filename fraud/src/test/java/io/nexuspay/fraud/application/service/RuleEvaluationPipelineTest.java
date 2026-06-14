package io.nexuspay.fraud.application.service;

import io.nexuspay.fraud.application.dto.PaymentContext;
import io.nexuspay.fraud.config.FraudProperties;
import io.nexuspay.fraud.domain.model.FraudRule;
import io.nexuspay.fraud.domain.model.RuleAction;
import io.nexuspay.fraud.domain.model.RuleCondition;
import io.nexuspay.fraud.domain.model.RuleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-027b: the velocity INCR in {@link RuleEvaluationPipeline} must be gated behind a Valkey SET-NX
 * first-seen marker keyed by (tenant, idempotency-key), so a retried request does NOT inflate the
 * sliding-window velocity counters. Pins: first-seen → exactly one INCR per (field, window); a
 * retry (marker already set) → zero INCR; a Valkey SET-NX error → fail OPEN (INCR runs); and TTL
 * window expiry → re-charge with the same key beyond the window counts again.
 */
class RuleEvaluationPipelineTest {

    private RuleEngine ruleEngine;
    private FraudProperties properties;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private RuleEvaluationPipeline pipeline;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ruleEngine = mock(RuleEngine.class);
        properties = new FraudProperties();
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        // The velocity-read (evaluateVelocityWithState) is not under test here — keep counts low.
        lenient().when(valueOps.get(anyString())).thenReturn("0");

        pipeline = new RuleEvaluationPipeline(ruleEngine, properties, redis);
    }

    /** A single VELOCITY rule over card_hash, 10-minute window. */
    private static FraudRule velocityRule() {
        FraudRule rule = new FraudRule(UUID.randomUUID(), "T", "card velocity", RuleType.VELOCITY,
                new RuleCondition(Map.of("field", "card_hash", "max_count", 3, "window_minutes", 10)),
                RuleAction.REVIEW, 30, 1, 1, true, "test");
        return rule;
    }

    private static PaymentContext ctx(String tenant, String key) {
        return new PaymentContext(key, tenant, 5000, "USD", "cust_1", "a@b.com",
                "411111", "card-hash-1", "1.1.1.1", "US", "dev", Map.of(), Map.of(), key);
    }

    private static final String EXPECTED_VELOCITY_KEY = "fraud:velocity:T:card_hash:card-hash-1:10m";
    private static final String EXPECTED_MARKER_KEY = "fraud:assessed:T:idem-1";

    @Test
    void firstSeen_incrementsVelocityExactlyOnce() {
        when(valueOps.setIfAbsent(eq(EXPECTED_MARKER_KEY), eq("1"), any(Duration.class))).thenReturn(true);

        pipeline.evaluate(ctx("T", "idem-1"), List.of(velocityRule()));

        verify(valueOps, times(1)).increment(EXPECTED_VELOCITY_KEY); // exactly one INCR for the (field,window)
    }

    @Test
    void retry_markerAlreadySet_skipsVelocityIncrementEntirely() {
        // SET-NX returns FALSE → this is a retry of an already-seen (tenant, key) → NO velocity INCR.
        when(valueOps.setIfAbsent(eq(EXPECTED_MARKER_KEY), eq("1"), any(Duration.class))).thenReturn(false);

        pipeline.evaluate(ctx("T", "idem-1"), List.of(velocityRule()));

        verify(valueOps, never()).increment(anyString()); // velocity NOT inflated by the retry
    }

    @Test
    void valkeyError_onMarker_failsOpen_incrementsVelocity() {
        // A SET-NX error must NOT silently drop velocity accounting → fail OPEN (INCR still runs).
        when(valueOps.setIfAbsent(eq(EXPECTED_MARKER_KEY), eq("1"), any(Duration.class)))
                .thenThrow(new RuntimeException("valkey down"));

        pipeline.evaluate(ctx("T", "idem-1"), List.of(velocityRule()));

        verify(valueOps, times(1)).increment(EXPECTED_VELOCITY_KEY);
    }

    @Test
    void ttlExpiry_sameKeyBeyondWindow_countsAgain() {
        // The marker is window-scoped (TTL), not infinite: after it expires, SET-NX succeeds again,
        // so a legitimately-distinct re-use of the same key beyond the TTL is re-counted (not swallowed).
        when(valueOps.setIfAbsent(eq(EXPECTED_MARKER_KEY), eq("1"), any(Duration.class)))
                .thenReturn(true)    // first charge: first-seen
                .thenReturn(false)   // immediate retry within window: deduped
                .thenReturn(true);   // beyond TTL: marker expired → counts again

        pipeline.evaluate(ctx("T", "idem-1"), List.of(velocityRule())); // INCR
        pipeline.evaluate(ctx("T", "idem-1"), List.of(velocityRule())); // skipped
        pipeline.evaluate(ctx("T", "idem-1"), List.of(velocityRule())); // INCR

        verify(valueOps, times(2)).increment(EXPECTED_VELOCITY_KEY); // 2 of 3 counted
    }

    @Test
    void markerKeyUsesConfiguredTtl() {
        properties.getIdempotency().setTtl(Duration.ofHours(6));
        when(valueOps.setIfAbsent(eq(EXPECTED_MARKER_KEY), eq("1"), eq(Duration.ofHours(6)))).thenReturn(true);

        pipeline.evaluate(ctx("T", "idem-1"), List.of(velocityRule()));

        verify(valueOps).setIfAbsent(eq(EXPECTED_MARKER_KEY), eq("1"), eq(Duration.ofHours(6)));
    }
}
