package io.nexuspay.common.exception;

public class PaymentException extends NexusPayException {

    public PaymentException(String message, String errorCode) {
        super(message, errorCode);
    }

    public PaymentException(String message, String errorCode, Throwable cause) {
        super(message, errorCode, cause);
    }

    public static PaymentException notFound(String paymentId) {
        return new PaymentException(
                "Payment not found: " + paymentId,
                "payment_not_found"
        );
    }

    public static PaymentException processingFailed(String reason) {
        return new PaymentException(
                "Payment processing failed: " + reason,
                "processing_failed"
        );
    }

    public static PaymentException gatewayError(String message, Throwable cause) {
        return new PaymentException(message, "gateway_error", cause);
    }

    public static PaymentException invalidState(String currentState, String attemptedAction) {
        return new PaymentException(
                "Cannot " + attemptedAction + " payment in state: " + currentState,
                "invalid_state"
        );
    }
}
