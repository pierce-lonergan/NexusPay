package io.nexuspay.workflow.application;

import java.io.Serializable;

/**
 * Result DTO from the payment workflow.
 *
 * @since 0.2.0 (Sprint 2.2)
 */
public record PaymentWorkflowResult(
        String paymentId,
        String status,
        String externalPaymentId,
        int attemptCount,
        String failureReason
) implements Serializable {

    public static PaymentWorkflowResult success(String paymentId, String externalPaymentId, int attempts) {
        return new PaymentWorkflowResult(paymentId, "SUCCEEDED", externalPaymentId, attempts, null);
    }

    public static PaymentWorkflowResult failed(String paymentId, int attempts, String reason) {
        return new PaymentWorkflowResult(paymentId, "FAILED", null, attempts, reason);
    }

    public static PaymentWorkflowResult cancelled(String paymentId, int attempts) {
        return new PaymentWorkflowResult(paymentId, "CANCELLED", null, attempts, "Cancelled by user");
    }

    public static PaymentWorkflowResult timedOut(String paymentId, int attempts) {
        return new PaymentWorkflowResult(paymentId, "TIMED_OUT", null, attempts, "Confirmation timeout");
    }
}
