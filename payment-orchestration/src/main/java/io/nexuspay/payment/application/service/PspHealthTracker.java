package io.nexuspay.payment.application.service;

import io.nexuspay.payment.application.port.routing.PspHealthRepository;
import io.nexuspay.payment.domain.routing.PspHealthSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Tracks PSP health metrics (auth rates, latency, circuit breaker state).
 * Provides aggregated health snapshots for routing decisions.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Service
public class PspHealthTracker {

    private static final Logger LOG = LoggerFactory.getLogger(PspHealthTracker.class);

    private final PspHealthRepository healthRepository;
    private final double unhealthyAuthRateThreshold;
    private final double unhealthyP95ThresholdMs;
    private final int minSampleSize;

    public PspHealthTracker(
            PspHealthRepository healthRepository,
            @Value("${nexuspay.routing.health.unhealthy-auth-rate-threshold:0.70}") double unhealthyAuthRateThreshold,
            @Value("${nexuspay.routing.health.unhealthy-p95-threshold-ms:3000}") double unhealthyP95ThresholdMs,
            @Value("${nexuspay.routing.health.min-sample-size:100}") int minSampleSize) {
        this.healthRepository = healthRepository;
        this.unhealthyAuthRateThreshold = unhealthyAuthRateThreshold;
        this.unhealthyP95ThresholdMs = unhealthyP95ThresholdMs;
        this.minSampleSize = minSampleSize;
    }

    /**
     * Records a payment attempt result for health tracking.
     */
    public void recordAttempt(String pspConnector, boolean success, long latencyMs) {
        healthRepository.recordAuthResult(pspConnector, success, latencyMs);
    }

    /**
     * Checks if a PSP is healthy enough for routing.
     */
    public boolean isHealthy(String pspConnector) {
        Optional<PspHealthSnapshot> snapshot = healthRepository.getHealth(pspConnector);
        return snapshot.map(s -> s.isHealthy(unhealthyAuthRateThreshold, unhealthyP95ThresholdMs))
                .orElse(true); // Assume healthy if no data yet
    }

    /**
     * Gets the current health snapshot for a PSP.
     */
    public Optional<PspHealthSnapshot> getHealth(String pspConnector) {
        return healthRepository.getHealth(pspConnector);
    }

    /**
     * Gets all PSP health snapshots.
     */
    public List<PspHealthSnapshot> getAllHealth() {
        return healthRepository.getAllHealth();
    }

    /**
     * Updates circuit breaker state for a PSP.
     */
    public void setCircuitBreakerOpen(String pspConnector, boolean open) {
        healthRepository.updateCircuitBreakerState(pspConnector, open);
        LOG.info("Circuit breaker for {} set to {}", pspConnector, open ? "OPEN" : "CLOSED");
    }
}
