package io.nexuspay.payment.domain.routing;

import java.util.List;

/**
 * Result of a cascade attempt across multiple PSPs.
 * Records which PSPs were tried, which succeeded/failed, and the final outcome.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
public record CascadeResult(
        String paymentId,
        boolean success,
        String finalPsp,
        int attemptCount,
        List<CascadeAttempt> attempts
) {

    /**
     * Individual cascade attempt result.
     */
    public record CascadeAttempt(
            String pspConnector,
            int attemptNumber,
            boolean succeeded,
            String declineCode,
            DeclineType declineType,
            long latencyMs
    ) {}

    public enum DeclineType {
        SOFT,
        HARD,
        TIMEOUT,
        ERROR
    }

    public static CascadeResult success(String paymentId, String psp, List<CascadeAttempt> attempts) {
        return new CascadeResult(paymentId, true, psp, attempts.size(), attempts);
    }

    public static CascadeResult failure(String paymentId, List<CascadeAttempt> attempts) {
        return new CascadeResult(paymentId, false, null, attempts.size(), attempts);
    }
}
