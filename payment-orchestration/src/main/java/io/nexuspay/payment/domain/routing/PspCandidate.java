package io.nexuspay.payment.domain.routing;

import java.math.BigDecimal;

/**
 * Represents a PSP connector as a routing candidate with its current health
 * and capability attributes.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
public record PspCandidate(
        String pspConnector,
        boolean circuitBreakerOpen,
        double authRate,
        double latencyP95Ms,
        BigDecimal effectiveFee,
        int weight,
        boolean supportsCurrency,
        boolean supportsDcc
) {

    /**
     * Whether this candidate is eligible for routing (not circuit-broken, supports currency).
     */
    public boolean isEligible() {
        return !circuitBreakerOpen && supportsCurrency;
    }

    /**
     * Creates a basic candidate with minimal attributes for testing.
     */
    public static PspCandidate basic(String connector) {
        return new PspCandidate(connector, false, 0.95, 200.0,
                BigDecimal.ZERO, 1, true, false);
    }
}
