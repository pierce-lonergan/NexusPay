package io.nexuspay.payment.application.service;

import io.nexuspay.payment.application.port.routing.RoutingConfigRepository;
import io.nexuspay.payment.application.port.routing.RoutingConfigRepository.RoutingConfig;
import io.nexuspay.payment.application.port.routing.RoutingDecisionRepository;
import io.nexuspay.payment.domain.routing.RoutingDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A/B testing service for routing strategies.
 * Splits traffic between two routing configurations and tracks results
 * for statistical significance testing.
 * <p>
 * Statistical significance is calculated using a two-proportion z-test
 * comparing auth (success) rates between groups A and B.
 *
 * @since 0.3.0 (Sprint 3.3)
 * @since 0.3.1 (GAP-047 — statistical significance via two-proportion z-test)
 */
@Service
public class RoutingAbTestService {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingAbTestService.class);

    /**
     * Standard normal z-values for common confidence levels (two-tailed).
     * 90% -> 1.645, 95% -> 1.960, 99% -> 2.576
     */
    private static final Map<Double, Double> Z_VALUES = Map.of(
            0.90, 1.645,
            0.95, 1.960,
            0.99, 2.576
    );

    private final RoutingConfigRepository configRepository;
    private final RoutingDecisionRepository decisionRepository;
    private final int minSampleSize;
    private final double confidenceLevel;
    private final boolean autoPromote;

    /**
     * In-memory counters for A/B test outcomes. Keyed by "abTestId:group".
     * Tracks success/total for each group to compute auth rates.
     * Updated by the payment orchestration layer after each payment attempt.
     */
    private final ConcurrentHashMap<String, AtomicLong> successCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> totalCounters = new ConcurrentHashMap<>();

    public RoutingAbTestService(
            RoutingConfigRepository configRepository,
            RoutingDecisionRepository decisionRepository,
            @Value("${nexuspay.routing.ab-testing.min-sample-size:1000}") int minSampleSize,
            @Value("${nexuspay.routing.ab-testing.confidence-level:0.95}") double confidenceLevel,
            @Value("${nexuspay.routing.ab-testing.auto-promote:true}") boolean autoPromote) {
        this.configRepository = configRepository;
        this.decisionRepository = decisionRepository;
        this.minSampleSize = minSampleSize;
        this.confidenceLevel = confidenceLevel;
        this.autoPromote = autoPromote;
    }

    /**
     * Selects which config to use for a request based on A/B test traffic split.
     *
     * @param configA primary config (control group)
     * @param configB test config
     * @return the selected config and its group label ("A" or "B")
     */
    public AbTestSelection selectConfig(RoutingConfig configA, RoutingConfig configB) {
        double splitRatio = configB.abTestTraffic();
        if (splitRatio <= 0 || splitRatio >= 1) {
            return new AbTestSelection(configA, "A");
        }

        double random = ThreadLocalRandom.current().nextDouble();
        if (random < splitRatio) {
            return new AbTestSelection(configB, "B");
        }
        return new AbTestSelection(configA, "A");
    }

    /**
     * Records a payment outcome for an A/B test group.
     * Called after a payment is processed to track auth rates per group.
     *
     * @param abTestId the A/B test identifier
     * @param group    "A" or "B"
     * @param success  whether the payment was authorized
     */
    public void recordOutcome(UUID abTestId, String group, boolean success) {
        if (abTestId == null || group == null) return;
        String key = abTestId + ":" + group;
        totalCounters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        if (success) {
            successCounters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        }
    }

    /**
     * Gets the A/B test results summary with statistical significance analysis.
     * <p>
     * Uses a two-proportion z-test to determine if the difference in auth rates
     * between groups A and B is statistically significant at the configured
     * confidence level.
     */
    public AbTestSummary getTestSummary(UUID abTestId) {
        List<RoutingDecision> decisions = decisionRepository.findByAbTestId(abTestId);

        long countA = decisions.stream().filter(d -> "A".equals(d.abTestGroup())).count();
        long countB = decisions.stream().filter(d -> "B".equals(d.abTestGroup())).count();

        // Get auth rate data from in-memory counters
        String keyA = abTestId + ":A";
        String keyB = abTestId + ":B";
        long totalA = totalCounters.getOrDefault(keyA, new AtomicLong(0)).get();
        long successA = successCounters.getOrDefault(keyA, new AtomicLong(0)).get();
        long totalB = totalCounters.getOrDefault(keyB, new AtomicLong(0)).get();
        long successB = successCounters.getOrDefault(keyB, new AtomicLong(0)).get();

        double authRateA = totalA > 0 ? (double) successA / totalA : 0.0;
        double authRateB = totalB > 0 ? (double) successB / totalB : 0.0;

        boolean hasSufficientData = countA >= minSampleSize && countB >= minSampleSize
                && totalA >= minSampleSize && totalB >= minSampleSize;

        // Compute statistical significance via two-proportion z-test
        SignificanceResult significance = hasSufficientData
                ? computeSignificance(successA, totalA, successB, totalB)
                : SignificanceResult.INSUFFICIENT_DATA;

        // Determine winner if significant
        String winner = null;
        if (significance.isSignificant()) {
            winner = authRateA > authRateB ? "A" : "B";
            LOG.info("A/B test {} has statistically significant result: winner={}, p-value={}, z-score={}",
                    abTestId, winner, significance.pValue(), significance.zScore());
        }

        return new AbTestSummary(
                abTestId,
                countA, countB,
                hasSufficientData,
                (double) countA / Math.max(1, countA + countB),
                authRateA, authRateB,
                significance.zScore(),
                significance.pValue(),
                significance.confidenceInterval(),
                significance.isSignificant(),
                winner
        );
    }

    /**
     * Two-proportion z-test for comparing auth rates between groups.
     * <p>
     * H0: pA = pB (auth rates are equal)
     * H1: pA != pB (auth rates differ)
     * <p>
     * z = (pA - pB) / sqrt(pPooled * (1 - pPooled) * (1/nA + 1/nB))
     * where pPooled = (successA + successB) / (nA + nB)
     */
    SignificanceResult computeSignificance(long successA, long nA, long successB, long nB) {
        if (nA == 0 || nB == 0) return SignificanceResult.INSUFFICIENT_DATA;

        double pA = (double) successA / nA;
        double pB = (double) successB / nB;
        double diff = pA - pB;

        // Pooled proportion under H0
        double pPooled = (double) (successA + successB) / (nA + nB);
        double qPooled = 1.0 - pPooled;

        // Standard error of the difference under H0
        double se = Math.sqrt(pPooled * qPooled * (1.0 / nA + 1.0 / nB));
        if (se == 0) {
            return new SignificanceResult(0.0, 1.0, 0.0, false);
        }

        // z-score
        double zScore = diff / se;

        // Two-tailed p-value
        double pValue = 2.0 * (1.0 - normalCdf(Math.abs(zScore)));

        // Confidence interval for the difference (pA - pB)
        double zCritical = Z_VALUES.getOrDefault(confidenceLevel, 1.960);
        double ciSe = Math.sqrt(pA * (1 - pA) / nA + pB * (1 - pB) / nB);
        double marginOfError = zCritical * ciSe;

        boolean significant = pValue < (1.0 - confidenceLevel);

        return new SignificanceResult(zScore, pValue, marginOfError, significant);
    }

    /**
     * Standard normal CDF approximation using Taylor series expansion.
     * Accurate to ~10^-7 for practical z-values.
     */
    static double normalCdf(double z) {
        if (z < -8.0) return 0.0;
        if (z > 8.0) return 1.0;

        double sum = 0.0;
        double term = z;
        for (int i = 3; sum + term != sum; i += 2) {
            sum = sum + term;
            term = term * z * z / i;
        }
        return 0.5 + sum * pdf(z);
    }

    private static double pdf(double z) {
        return Math.exp(-0.5 * z * z) / Math.sqrt(2.0 * Math.PI);
    }

    public record AbTestSelection(RoutingConfig config, String group) {}

    public record AbTestSummary(
            UUID abTestId,
            long groupACount,
            long groupBCount,
            boolean hasSufficientData,
            double groupATrafficShare,
            double groupAAuthRate,
            double groupBAuthRate,
            double zScore,
            double pValue,
            double confidenceInterval,
            boolean isStatisticallySignificant,
            String winner
    ) {}

    record SignificanceResult(double zScore, double pValue, double confidenceInterval, boolean isSignificant) {
        static final SignificanceResult INSUFFICIENT_DATA = new SignificanceResult(0.0, 1.0, 0.0, false);
    }
}
