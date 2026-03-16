package io.nexuspay.payment.application.service;

import io.nexuspay.payment.application.port.routing.PspHealthRepository;
import io.nexuspay.payment.domain.routing.PspHealthSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full circuit breaker state machine for PSP connectors with automatic recovery.
 * <p>
 * State transitions:
 * <pre>
 *   CLOSED ─(failure threshold exceeded)─> OPEN
 *   OPEN ─(cooldown elapsed)─> HALF_OPEN
 *   HALF_OPEN ─(probe succeeds)─> CLOSED
 *   HALF_OPEN ─(probe fails)─> OPEN (reset cooldown)
 * </pre>
 * <p>
 * The manager runs a scheduled check that transitions OPEN breakers to HALF_OPEN
 * after the cooldown period. In HALF_OPEN state, a limited number of probe requests
 * are allowed through. If probes succeed, the breaker closes; if they fail, it reopens.
 *
 * @since 0.3.1 (GAP-048)
 */
@Service
public class CircuitBreakerManager {

    private static final Logger LOG = LoggerFactory.getLogger(CircuitBreakerManager.class);

    private final PspHealthRepository healthRepository;
    private final double failureRateThreshold;
    private final int failureCountThreshold;
    private final Duration cooldownDuration;
    private final int probeRequestsInHalfOpen;

    /**
     * Tracks per-PSP circuit breaker state.
     */
    private final ConcurrentHashMap<String, CircuitBreakerState> states = new ConcurrentHashMap<>();

    public CircuitBreakerManager(
            PspHealthRepository healthRepository,
            @Value("${nexuspay.routing.circuit-breaker.failure-rate-threshold:0.50}") double failureRateThreshold,
            @Value("${nexuspay.routing.circuit-breaker.failure-count-threshold:10}") int failureCountThreshold,
            @Value("${nexuspay.routing.circuit-breaker.cooldown-seconds:60}") long cooldownSeconds,
            @Value("${nexuspay.routing.circuit-breaker.probe-requests:3}") int probeRequestsInHalfOpen) {
        this.healthRepository = healthRepository;
        this.failureRateThreshold = failureRateThreshold;
        this.failureCountThreshold = failureCountThreshold;
        this.cooldownDuration = Duration.ofSeconds(cooldownSeconds);
        this.probeRequestsInHalfOpen = probeRequestsInHalfOpen;
    }

    /**
     * Records the outcome of a payment attempt and evaluates circuit breaker transitions.
     *
     * @param pspConnector the PSP connector name
     * @param success      whether the attempt succeeded
     */
    public void recordAttempt(String pspConnector, boolean success) {
        CircuitBreakerState state = states.computeIfAbsent(pspConnector,
                k -> new CircuitBreakerState(Status.CLOSED));

        synchronized (state) {
            switch (state.status) {
                case CLOSED -> {
                    state.recordAttempt(success);
                    if (shouldTrip(state)) {
                        tripBreaker(pspConnector, state);
                    }
                }
                case HALF_OPEN -> {
                    state.probeResults++;
                    if (success) {
                        state.probeSuccesses++;
                    }
                    // Check if we've completed all probe requests
                    if (state.probeResults >= probeRequestsInHalfOpen) {
                        if (state.probeSuccesses == state.probeResults) {
                            // All probes succeeded — close the breaker
                            closeBreaker(pspConnector, state);
                        } else {
                            // At least one probe failed — reopen
                            tripBreaker(pspConnector, state);
                        }
                    }
                }
                case OPEN -> {
                    // Should not happen in normal flow (open breaker = requests blocked)
                    // but record anyway in case of race condition
                    LOG.warn("Attempt recorded for OPEN circuit breaker on {}", pspConnector);
                }
            }
        }
    }

    /**
     * Whether a request is allowed through to this PSP.
     * - CLOSED: always allow
     * - HALF_OPEN: allow up to probeRequestsInHalfOpen
     * - OPEN: block
     */
    public boolean isRequestAllowed(String pspConnector) {
        CircuitBreakerState state = states.get(pspConnector);
        if (state == null) return true;

        synchronized (state) {
            return switch (state.status) {
                case CLOSED -> true;
                case HALF_OPEN -> state.probeResults < probeRequestsInHalfOpen;
                case OPEN -> false;
            };
        }
    }

    /**
     * Gets the current circuit breaker status for a PSP.
     */
    public Status getStatus(String pspConnector) {
        CircuitBreakerState state = states.get(pspConnector);
        return state != null ? state.status : Status.CLOSED;
    }

    /**
     * Gets detailed circuit breaker info for all PSPs.
     */
    public Map<String, CircuitBreakerInfo> getAllStates() {
        var result = new ConcurrentHashMap<String, CircuitBreakerInfo>();
        states.forEach((psp, state) -> {
            synchronized (state) {
                result.put(psp, new CircuitBreakerInfo(
                        state.status,
                        state.failureCount,
                        state.totalCount,
                        state.failureRate(),
                        state.openedAt,
                        state.probeResults,
                        state.probeSuccesses
                ));
            }
        });
        return result;
    }

    /**
     * Manually force a circuit breaker open or closed.
     */
    public void forceState(String pspConnector, Status targetStatus) {
        CircuitBreakerState state = states.computeIfAbsent(pspConnector,
                k -> new CircuitBreakerState(Status.CLOSED));

        synchronized (state) {
            Status oldStatus = state.status;
            state.status = targetStatus;
            if (targetStatus == Status.OPEN) {
                state.openedAt = Instant.now();
                healthRepository.updateCircuitBreakerState(pspConnector, true);
            } else if (targetStatus == Status.CLOSED) {
                state.resetCounters();
                healthRepository.updateCircuitBreakerState(pspConnector, false);
            }
            LOG.info("Circuit breaker for {} manually forced from {} to {}", pspConnector, oldStatus, targetStatus);
        }
    }

    /**
     * Scheduled task: checks all OPEN breakers and transitions them to HALF_OPEN
     * once the cooldown period has elapsed.
     */
    @Scheduled(fixedDelayString = "${nexuspay.routing.circuit-breaker.check-interval-ms:5000}")
    public void checkCooldowns() {
        Instant now = Instant.now();
        states.forEach((psp, state) -> {
            synchronized (state) {
                if (state.status == Status.OPEN && state.openedAt != null) {
                    Duration elapsed = Duration.between(state.openedAt, now);
                    if (elapsed.compareTo(cooldownDuration) >= 0) {
                        state.status = Status.HALF_OPEN;
                        state.probeResults = 0;
                        state.probeSuccesses = 0;
                        healthRepository.updateCircuitBreakerState(psp, false); // allow probe traffic
                        LOG.info("Circuit breaker for {} transitioned OPEN -> HALF_OPEN after {}s cooldown",
                                psp, cooldownDuration.getSeconds());
                    }
                }
            }
        });
    }

    private boolean shouldTrip(CircuitBreakerState state) {
        return state.totalCount >= failureCountThreshold
                && state.failureRate() >= failureRateThreshold;
    }

    private void tripBreaker(String pspConnector, CircuitBreakerState state) {
        Status oldStatus = state.status;
        state.status = Status.OPEN;
        state.openedAt = Instant.now();
        state.resetCounters();
        healthRepository.updateCircuitBreakerState(pspConnector, true);
        LOG.warn("Circuit breaker TRIPPED for {} ({} -> OPEN): failure rate {}/{} = {:.1f}%",
                pspConnector, oldStatus, state.failureCount, state.totalCount,
                state.failureRate() * 100);
    }

    private void closeBreaker(String pspConnector, CircuitBreakerState state) {
        state.status = Status.CLOSED;
        state.resetCounters();
        state.openedAt = null;
        healthRepository.updateCircuitBreakerState(pspConnector, false);
        LOG.info("Circuit breaker CLOSED for {} — all {} probe requests succeeded",
                pspConnector, probeRequestsInHalfOpen);
    }

    // --- Inner types ---

    public enum Status {
        /** Normal operation — all requests allowed. */
        CLOSED,
        /** Failures exceeded threshold — requests blocked, waiting for cooldown. */
        OPEN,
        /** Cooldown elapsed — limited probe requests allowed to test recovery. */
        HALF_OPEN
    }

    public record CircuitBreakerInfo(
            Status status,
            int failureCount,
            int totalCount,
            double failureRate,
            Instant openedAt,
            int probeResults,
            int probeSuccesses
    ) {}

    static class CircuitBreakerState {
        volatile Status status;
        int failureCount;
        int totalCount;
        Instant openedAt;
        int probeResults;
        int probeSuccesses;

        CircuitBreakerState(Status status) {
            this.status = status;
        }

        void recordAttempt(boolean success) {
            totalCount++;
            if (!success) failureCount++;
        }

        double failureRate() {
            return totalCount > 0 ? (double) failureCount / totalCount : 0.0;
        }

        void resetCounters() {
            failureCount = 0;
            totalCount = 0;
            probeResults = 0;
            probeSuccesses = 0;
        }
    }
}
