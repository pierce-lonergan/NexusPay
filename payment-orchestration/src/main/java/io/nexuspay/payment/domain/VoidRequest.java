package io.nexuspay.payment.domain;

/**
 * Request to void/cancel an authorization before capture.
 * Void is the free compensation action — no fees incurred unlike refund.
 * Used by saga compensation logic.
 */
public record VoidRequest(
        String cancellationReason,
        String idempotencyKey
) {
}
