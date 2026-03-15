package io.nexuspay.payment.adapter.out.hyperswitch;

import io.nexuspay.payment.adapter.out.hyperswitch.dto.HsPaymentResponse;
import io.nexuspay.payment.adapter.out.hyperswitch.dto.HsRefundResponse;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundResponse;

/**
 * Maps HyperSwitch-specific DTOs to NexusPay domain objects.
 * This is the translation boundary — all HyperSwitch API quirks are absorbed here.
 *
 * Status mapping:
 *   HyperSwitch uses snake_case statuses (requires_capture, requires_payment_method).
 *   NexusPay uses the same conventions for transparency.
 */
final class HyperSwitchResponseMapper {

    private HyperSwitchResponseMapper() {
    }

    static PaymentResponse toPaymentResponse(HsPaymentResponse hs) {
        return new PaymentResponse(
                hs.paymentId(),
                normalizePaymentStatus(hs.status()),
                hs.amount(),
                hs.currency() != null ? hs.currency().toUpperCase() : null,
                hs.captureMethod(),
                hs.customerId(),
                hs.connector(),
                hs.connectorTransactionId(),
                hs.errorCode(),
                hs.errorMessage(),
                hs.created(),
                hs.metadata()
        );
    }

    static RefundResponse toRefundResponse(HsRefundResponse hs) {
        return new RefundResponse(
                hs.refundId(),
                hs.paymentId(),
                normalizeRefundStatus(hs.status()),
                hs.amount(),
                hs.currency() != null ? hs.currency().toUpperCase() : null,
                hs.reason(),
                hs.connector(),
                hs.connectorRefundId(),
                hs.errorCode(),
                hs.errorMessage(),
                hs.createdAt()
        );
    }

    /**
     * Normalizes HyperSwitch payment statuses to NexusPay conventions.
     * HyperSwitch uses PascalCase internally but snake_case in API responses.
     */
    private static String normalizePaymentStatus(String hsStatus) {
        if (hsStatus == null) return null;
        return switch (hsStatus.toLowerCase().replace(" ", "_")) {
            case "requires_payment_method" -> PaymentResponse.STATUS_REQUIRES_PAYMENT_METHOD;
            case "requires_confirmation" -> PaymentResponse.STATUS_REQUIRES_CONFIRMATION;
            case "requires_capture" -> PaymentResponse.STATUS_REQUIRES_CAPTURE;
            case "processing" -> PaymentResponse.STATUS_PROCESSING;
            case "succeeded", "charged" -> PaymentResponse.STATUS_SUCCEEDED;
            case "failed" -> PaymentResponse.STATUS_FAILED;
            case "cancelled", "voided" -> PaymentResponse.STATUS_CANCELLED;
            default -> hsStatus.toLowerCase();
        };
    }

    private static String normalizeRefundStatus(String hsStatus) {
        if (hsStatus == null) return null;
        return switch (hsStatus.toLowerCase()) {
            case "succeeded", "success" -> RefundResponse.STATUS_SUCCEEDED;
            case "failed", "failure" -> RefundResponse.STATUS_FAILED;
            case "pending", "manual_review" -> RefundResponse.STATUS_PENDING;
            default -> hsStatus.toLowerCase();
        };
    }
}
