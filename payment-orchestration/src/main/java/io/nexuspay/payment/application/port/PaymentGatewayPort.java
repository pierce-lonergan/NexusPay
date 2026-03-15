package io.nexuspay.payment.application.port;

import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.RefundResponse;
import io.nexuspay.payment.domain.CaptureRequest;
import io.nexuspay.payment.domain.ConfirmRequest;
import io.nexuspay.payment.domain.VoidRequest;

import java.util.List;

/**
 * Primary port for payment gateway operations.
 * Abstracts the underlying payment processor (HyperSwitch) behind a clean domain interface.
 *
 * All methods propagate the caller's idempotency key to the gateway to prevent
 * double-charges on retries (see: Implementation Detail #6 in the Phase 1 plan).
 *
 * Implementations must be wrapped with circuit breakers (Resilience4j).
 */
public interface PaymentGatewayPort {

    /**
     * Creates a new payment at the gateway.
     * Maps to HyperSwitch POST /payments.
     */
    PaymentResponse createPayment(PaymentRequest request);

    /**
     * Confirms/authorizes a payment that requires confirmation.
     * Maps to HyperSwitch POST /payments/{id}/confirm.
     */
    PaymentResponse confirmPayment(String paymentId, ConfirmRequest request);

    /**
     * Captures a previously authorized payment.
     * Maps to HyperSwitch POST /payments/{id}/capture.
     */
    PaymentResponse capturePayment(String paymentId, CaptureRequest request);

    /**
     * Voids/cancels an authorization before capture.
     * This is the free compensation action — no fees incurred (unlike refund).
     * Maps to HyperSwitch POST /payments/{id}/cancel.
     */
    PaymentResponse voidPayment(String paymentId, VoidRequest request);

    /**
     * Retrieves a payment by its gateway ID.
     * Maps to HyperSwitch GET /payments/{id}.
     */
    PaymentResponse getPayment(String paymentId);

    /**
     * Creates a refund against a captured payment.
     * Maps to HyperSwitch POST /refunds.
     */
    RefundResponse createRefund(RefundRequest request);

    /**
     * Retrieves a refund by its gateway ID.
     * Maps to HyperSwitch GET /refunds/{id}.
     */
    RefundResponse getRefund(String refundId);
}
