package io.nexuspay.fraud.application.service;

import io.nexuspay.fraud.application.dto.PaymentContext;
import io.nexuspay.fraud.config.FraudProperties;
import io.nexuspay.fraud.domain.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates three-phase fraud rule evaluation:
 * <ol>
 *   <li><b>Phase 1</b>: Pre-auth rules — velocity, geo, BIN, amount threshold</li>
 *   <li><b>Phase 2</b>: Scoring — aggregate signals into numeric score (0-100)</li>
 *   <li><b>Phase 3</b>: Decision — apply thresholds → ALLOW / REVIEW / BLOCK</li>
 * </ol>
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Service
public class RuleEvaluationPipeline {

    private static final Logger log = LoggerFactory.getLogger(RuleEvaluationPipeline.class);

    private final RuleEngine ruleEngine;
    private final FraudProperties fraudProperties;
    private final StringRedisTemplate redisTemplate;

    public RuleEvaluationPipeline(RuleEngine ruleEngine,
                                   FraudProperties fraudProperties,
                                   StringRedisTemplate redisTemplate) {
        this.ruleEngine = ruleEngine;
        this.fraudProperties = fraudProperties;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Evaluates active rules against the payment context and returns a native risk score.
     *
     * @param context     payment context
     * @param activeRules active fraud rules for the tenant, sorted by priority
     * @return native risk assessment with score and triggered rules
     */
    public NativeEvaluation evaluate(PaymentContext context, List<FraudRule> activeRules) {
        List<RiskSignal> signals = new ArrayList<>();
        List<String> triggeredRuleIds = new ArrayList<>();
        boolean immediateBlock = false;
        boolean flagForReview = false;

        // Phase 1: Pre-auth rule evaluation
        double abTestHash = computeAbTestHash(context.paymentId());

        for (FraudRule rule : activeRules) {
            if (!rule.shouldEvaluate(abTestHash)) continue;

            // Special handling for velocity rules — check actual count in Valkey
            if (rule.getRuleType() == RuleType.VELOCITY) {
                RiskSignal velocitySignal = evaluateVelocityWithState(rule, context);
                if (velocitySignal != null) {
                    signals.add(velocitySignal);
                    triggeredRuleIds.add(rule.getId().toString());
                    if (rule.getAction() == RuleAction.BLOCK) immediateBlock = true;
                    if (rule.getAction() == RuleAction.REVIEW) flagForReview = true;
                }
                continue;
            }

            RiskSignal signal = ruleEngine.evaluate(rule, context);
            if (signal != null) {
                signals.add(signal);
                triggeredRuleIds.add(rule.getId().toString());
                if (rule.getAction() == RuleAction.BLOCK) immediateBlock = true;
                if (rule.getAction() == RuleAction.REVIEW) flagForReview = true;
            }
        }

        // Phase 2: Score aggregation
        int nativeScore = computeNativeScore(signals);

        // Phase 3: Decision
        RiskDecision preDecision;
        if (immediateBlock || nativeScore >= fraudProperties.getNativeRules().getDefaultBlockThreshold()) {
            preDecision = RiskDecision.BLOCK;
        } else if (flagForReview || nativeScore >= fraudProperties.getNativeRules().getDefaultReviewThreshold()) {
            preDecision = RiskDecision.REVIEW;
        } else {
            preDecision = RiskDecision.ALLOW;
        }

        // Increment velocity counters for this payment (sliding window) — but ONLY the first time we
        // see this (tenant, idempotency-key). The FraudAssessmentService read-through + unique index
        // dedup the ROW/event, but the velocity INCR happens here BEFORE the save, so two truly-
        // concurrent retries could both INCR before either saves. A Valkey SET-NX first-seen marker
        // closes that window (B-027b). On a Valkey error → fail-OPEN (INCR), matching the existing
        // fail-open posture in evaluateVelocityWithState — an outage must not silently drop accounting.
        if (markFirstSeen(context)) {
            incrementVelocityCounters(context, activeRules);
        } else {
            log.info("Velocity INCR skipped (idempotent retry): tenant={}, key={}",
                    context.tenantId(), context.dedupKey());
        }

        return new NativeEvaluation(nativeScore, preDecision, signals, triggeredRuleIds);
    }

    /**
     * SET-NX first-seen marker for a (tenant, idempotency-key). Returns true when THIS call is the
     * first to see the key (→ proceed with velocity INCR), false when a prior call already marked it
     * (→ a retry; skip the INCR). Fails OPEN (returns true) on a Valkey error so an outage cannot
     * silently drop fraud velocity accounting.
     */
    private boolean markFirstSeen(PaymentContext context) {
        String dedupKey = context.dedupKey();
        if (dedupKey == null || dedupKey.isBlank()) {
            return true; // no key to dedup on → always count
        }
        String markerKey = String.format("fraud:assessed:%s:%s", context.tenantId(), dedupKey);
        try {
            Boolean first = redisTemplate.opsForValue()
                    .setIfAbsent(markerKey, "1", fraudProperties.getIdempotency().getTtl());
            return !Boolean.FALSE.equals(first); // null (unexpected) → fail open
        } catch (Exception e) {
            log.warn("First-seen marker SET-NX failed for {} — failing OPEN (will INCR velocity): {}",
                    markerKey, e.getMessage());
            return true;
        }
    }

    /**
     * Evaluates a velocity rule by checking the count in Valkey.
     */
    private RiskSignal evaluateVelocityWithState(FraudRule rule, PaymentContext context) {
        String field = rule.getCondition().getString("field");
        int maxCount = rule.getCondition().getInt("max_count", Integer.MAX_VALUE);
        int windowMinutes = rule.getCondition().getInt("window_minutes", 60);

        String fieldValue = resolveVelocityField(field, context);
        if (fieldValue == null) return null;

        String key = velocityKey(context.tenantId(), field, fieldValue, windowMinutes);

        try {
            String countStr = redisTemplate.opsForValue().get(key);
            int count = countStr != null ? Integer.parseInt(countStr) : 0;

            if (count >= maxCount) {
                return new RiskSignal(
                        "velocity_rule",
                        rule.getRuleName(),
                        rule.getScoreAdjustment(),
                        String.format("Velocity exceeded: %d/%d in %d minutes for %s=%s",
                                count, maxCount, windowMinutes, field, fieldValue)
                );
            }
        } catch (Exception e) {
            log.warn("Failed to check velocity in Valkey for rule {}: {}", rule.getRuleName(), e.getMessage());
            // Fail open — don't block on cache failure
        }
        return null;
    }

    /**
     * After evaluation, increment velocity counters so the next payment has accurate counts.
     *
     * <p>Each distinct {@code (field, window)} counter is incremented exactly
     * once per payment — multiple rules over the same field/window share a
     * counter and must not multiply it. The TTL is set only when the counter is
     * first created (INCR returns 1); resetting the expiry on every increment
     * would turn a fixed window into an ever-growing lifetime count.</p>
     */
    private void incrementVelocityCounters(PaymentContext context, List<FraudRule> activeRules) {
        Map<String, Integer> windowByKey = new HashMap<>();
        for (FraudRule rule : activeRules) {
            if (rule.getRuleType() != RuleType.VELOCITY) continue;

            String field = rule.getCondition().getString("field");
            int windowMinutes = rule.getCondition().getInt("window_minutes", 60);
            String fieldValue = resolveVelocityField(field, context);
            if (fieldValue == null) continue;

            windowByKey.putIfAbsent(
                    velocityKey(context.tenantId(), field, fieldValue, windowMinutes), windowMinutes);
        }

        windowByKey.forEach((key, windowMinutes) -> {
            try {
                Long newCount = redisTemplate.opsForValue().increment(key);
                if (newCount != null && newCount == 1L) {
                    redisTemplate.expire(key, Duration.ofMinutes(windowMinutes));
                }
            } catch (Exception e) {
                log.warn("Failed to increment velocity counter: {}", e.getMessage());
            }
        });
    }

    /** Velocity counter key — includes the window so different windows don't share a counter. */
    private static String velocityKey(String tenantId, String field, String fieldValue, int windowMinutes) {
        return String.format("fraud:velocity:%s:%s:%s:%dm", tenantId, field, fieldValue, windowMinutes);
    }

    private String resolveVelocityField(String field, PaymentContext context) {
        if (field == null) return null;
        return switch (field) {
            case "card_hash" -> context.cardHash();
            case "customer_id" -> context.customerId();
            case "ip_address" -> context.ipAddress();
            case "customer_email" -> context.customerEmail();
            case "device_fingerprint" -> context.deviceFingerprintHash();
            default -> null;
        };
    }

    private int computeNativeScore(List<RiskSignal> signals) {
        if (signals.isEmpty()) return 0;
        int totalScore = signals.stream().mapToInt(RiskSignal::score).sum();
        return Math.max(0, Math.min(100, totalScore));
    }

    private double computeAbTestHash(String paymentId) {
        // Deterministic hash for A/B test traffic splitting
        return Math.abs(paymentId.hashCode() % 10000) / 10000.0;
    }

    /**
     * Result of native rule evaluation before FRM provider scores are combined.
     */
    public record NativeEvaluation(
            int nativeScore,
            RiskDecision preDecision,
            List<RiskSignal> signals,
            List<String> triggeredRuleIds
    ) {}
}
