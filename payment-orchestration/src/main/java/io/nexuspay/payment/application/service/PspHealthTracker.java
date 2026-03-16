package io.nexuspay.payment.application.service;

import io.nexuspay.payment.application.port.routing.PspHealthRepository;
import io.nexuspay.payment.domain.routing.PspHealthSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tracks PSP health metrics (auth rates, latency, circuit breaker state).
 * Provides aggregated health snapshots for routing decisions.
 * <p>
 * Integrates with {@link CircuitBreakerManager} for automatic circuit breaker
 * state management with cooldown and probe-based recovery.
 *
 * @since 0.3.0 (Sprint 3.3)
 * @since 0.3.1 (GAP-048 — circuit breaker state machine integration)
 */
@Service
public class PspHealthTracker {

    private static final Logger LOG = LoggerFactory.getLogger(PspHealthTracker.class);

    private final PspHealthRepository healthRepository;
    private final CircuitBreakerManager circuitBreakerManager;
    private final double unhealthyAuthRateThreshold;
    private final double unhealthyP95ThresholdMs;
    private final int minSampleSize;

    public PspHealthTracker(
            PspHealthRepository healthRepository,
            CircuitBreakerManager circuitBreakerManager,
            @Value("${nexuspay.routing.health.unhealthy-auth-rate-threshold:0.70}") double unhealthyAuthRateThreshold,
            @Value("${nexuspay.routing.health.unhealthy-p95-threshold-ms:3000}") double unhealthyP95ThresholdMs,
            @Value("${nexuspay.routing.health.min-sample-size:100}") int minSampleSize) {
        this.healthRepository = healthRepository;
        this.circuitBreakerManager = circuitBreakerManager;
        this.unhealthyAuthRateThreshold = unhealthyAuthRateThreshold;
        this.unhealthyP95ThresholdMs = unhealthyP95ThresholdMs;
        this.minSampleSize = minSampleSize;
    }

    /**
     * Records a payment attempt result for health tracking and circuit breaker evaluation.
     */
    public void recordAttempt(String pspConnector, boolean success, long latencyMs) {
        healthRepository.recordAuthResult(pspConnector, success, latencyMs);
        circuitBreakerManager.recordAttempt(pspConnector, success);
    }

    /**
     * Checks if a PSP is healthy enough for routing.
     * Considers both health metrics and circuit breaker state.
     */
    public boolean isHealthy(String pspConnector) {
        // Circuit breaker takes precedence
        if (!circuitBreakerManager.isRequestAllowed(pspConnector)) {
            return false;
        }

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
     * Gets the circuit breaker status for a PSP.
     */
    public CircuitBreakerManager.Status getCircuitBreakerStatus(String pspConnector) {
        return circuitBreakerManager.getStatus(pspConnector);
    }

    /**
     * Gets circuit breaker info for all PSPs.
     */
    public Map<String, CircuitBreakerManager.CircuitBreakerInfo> getAllCircuitBreakerStates() {
        return circuitBreakerManager.getAllStates();
    }

    /**
     * Manually force a circuit breaker open or closed.
     */
    public void forceCircuitBreakerState(String pspConnector, CircuitBreakerManager.Status status) {
        circuitBreakerManager.forceState(pspConnector, status);
    }
}
