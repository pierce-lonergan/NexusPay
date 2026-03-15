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
}
