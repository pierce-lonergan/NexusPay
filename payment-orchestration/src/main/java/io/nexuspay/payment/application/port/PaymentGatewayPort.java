package io.nexuspay.payment.application.port;

import io.nexuspay.payment.application.screening.CallContext;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.RefundResponse;
import io.nexuspay.payment.domain.CaptureRequest;
import io.nexuspay.payment.domain.ConfirmRequest;
import io.nexuspay.payment.domain.VoidRequest;

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
     *
     * <p>This 1-arg form sources the screening mode + tenant from request metadata (the
     * pre-B-029 behavior, kept only as a transitional fallback). New callers MUST use the
     * {@link #createPayment(PaymentRequest, CallContext)} overload to supply a TRUSTED
     * call-site identity, so the screening gate ignores any client-supplied
     * {@code source}/{@code workflow}/{@code tenant_id} markers.</p>
     */
    PaymentResponse createPayment(PaymentRequest request);

    /**
     * Creates a new payment, screening it with a TRUSTED server-set {@link CallContext}
     * (B-029). The {@code GatedPaymentGateway} takes the screening mode + tenant from
     * {@code ctx} and treats any client-supplied mode/tenant marker in the request metadata
     * as advisory (ignored). The default delegates to the 1-arg form for non-screening
     * implementations (e.g. the raw {@code HyperSwitchPaymentAdapter}, which has no gate).
     */
    default PaymentResponse createPayment(PaymentRequest request, CallContext ctx) {
        return createPayment(request);
    }

    /**
     * Confirms/authorizes a payment that requires confirmation.
     * Maps to HyperSwitch POST /payments/{id}/confirm.
     *
     * <p>This 2-arg form sources the screening mode + tenant from the persisted intent's
     * server-owned origin record (B-029). New callers SHOULD use the
     * {@link #confirmPayment(String, ConfirmRequest, CallContext)} overload to assert the
     * trusted ingress identity explicitly.</p>
     */
    PaymentResponse confirmPayment(String paymentId, ConfirmRequest request);

    /**
     * Confirms a payment, asserting the TRUSTED server-set {@link CallContext} (B-029).
     * The default delegates to the 2-arg form for non-screening implementations.
     */
    default PaymentResponse confirmPayment(String paymentId, ConfirmRequest request, CallContext ctx) {
        return confirmPayment(paymentId, request);
    }

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
