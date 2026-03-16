package io.nexuspay.payment.domain.routing;

import java.time.Instant;

/**
 * Point-in-time health snapshot for a PSP connector, used by routing strategies
 * to filter unhealthy PSPs and rank by performance.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
public record PspHealthSnapshot(
        String pspConnector,
        double authRate,
        long totalTransactions,
        double latencyP50Ms,
        double latencyP95Ms,
        double latencyP99Ms,
        boolean circuitBreakerOpen,
        Instant snapshotAt
) {

    /**
     * Whether this PSP has sufficient sample size for statistical confidence.
     */
    public boolean hasSufficientData(int minSampleSize) {
        return totalTransactions >= minSampleSize;
    }

    /**
     * Whether this PSP is considered healthy based on auth rate and latency thresholds.
     */
    public boolean isHealthy(double minAuthRate, double maxP95LatencyMs) {
        return !circuitBreakerOpen
                && authRate >= minAuthRate
                && latencyP95Ms <= maxP95LatencyMs;
    }
}
