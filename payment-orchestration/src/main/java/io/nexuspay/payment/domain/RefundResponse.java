package io.nexuspay.payment.domain;

import java.time.Instant;

/**
 * Domain response for a refund operation.
 */
public record RefundResponse(
        String gatewayRefundId,
        String paymentId,
        String status,
        long amount,
        String currency,
        String reason,
        String connectorName,
        String connectorRefundId,
        String errorCode,
        String errorMessage,
        Instant createdAt
) {
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";

    public boolean isSuccessful() {
        return STATUS_SUCCEEDED.equals(status);
    }

    /**
     * GAP-078 (critique v3 F5): returns a copy of this refund with {@code createdAt} replaced, preserving
     * every other field. Used by {@code GatedPaymentGateway}'s mock refund branch to re-stamp the mock's
     * createdAt with the per-tenant TEST CLOCK's frozen instant (which the GAP-076 refund projection then
     * inherits). Touches ONLY the createdAt — never a live-rail timestamp.
     */
    public RefundResponse withCreatedAt(Instant createdAt) {
        return new RefundResponse(
                gatewayRefundId, paymentId, status, amount, currency, reason, connectorName,
                connectorRefundId, errorCode, errorMessage, createdAt);
    }
}
