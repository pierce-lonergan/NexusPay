package io.nexuspay.payment.domain;

/**
 * Request to create a refund against a captured payment.
 */
public record RefundRequest(
        String paymentId,
        long amount,
        String currency,
        String reason,
        String idempotencyKey
) {
    public RefundRequest {
        if (amount <= 0) throw new IllegalArgumentException("refund amount must be positive");
        if (paymentId == null || paymentId.isBlank()) throw new IllegalArgumentException("paymentId required");
    }
}
