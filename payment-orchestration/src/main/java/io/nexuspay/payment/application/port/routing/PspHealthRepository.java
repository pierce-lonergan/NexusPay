package io.nexuspay.payment.application.port.routing;

import io.nexuspay.payment.domain.routing.PspHealthSnapshot;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for PSP health data (auth rates, latency, circuit breaker state).
 *
 * @since 0.3.0 (Sprint 3.3)
 */
public interface PspHealthRepository {

    Optional<PspHealthSnapshot> getHealth(String pspConnector);

    List<PspHealthSnapshot> getAllHealth();

    void recordAuthResult(String pspConnector, boolean success, long latencyMs);

    void updateCircuitBreakerState(String pspConnector, boolean open);
}
