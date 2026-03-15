package io.nexuspay.payment.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Domain response representing a payment's current state.
 * Unified across all gateway providers.
 */
public record PaymentResponse(
        String gatewayPaymentId,
        String status,
        long amount,
        String currency,
        String captureMethod,
        String customerId,
        String connectorName,
        String connectorTransactionId,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Map<String, Object> metadata
) {
    /**
     * Payment lifecycle statuses (mirrors HyperSwitch states).
     */
    public static final String STATUS_REQUIRES_PAYMENT_METHOD = "requires_payment_method";
    public static final String STATUS_REQUIRES_CONFIRMATION = "requires_confirmation";
    public static final String STATUS_REQUIRES_CAPTURE = "requires_capture";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    public boolean isSuccessful() {
        return STATUS_SUCCEEDED.equals(status);
    }

    public boolean requiresCapture() {
        return STATUS_REQUIRES_CAPTURE.equals(status);
    }

    public boolean isFailed() {
        return STATUS_FAILED.equals(status);
    }
}
