package io.nexuspay.payment.application.screening;

import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.payment.adapter.out.hyperswitch.HyperSwitchPaymentAdapter;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.domain.CaptureRequest;
import io.nexuspay.payment.domain.ConfirmRequest;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.RefundResponse;
import io.nexuspay.payment.domain.VoidRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code @Primary} {@link PaymentGatewayPort} decorator (B-024) that runs the fraud +
 * sanctions {@link PreAuthorizationGate} at the PORT boundary, so EVERY caller of the
 * payment port is screened — the gateway REST controller, billing renewals/dunning, the
 * SDK checkout path, and workflow activities — not just the one REST create endpoint.
 *
 * <p>Because {@link HyperSwitchPaymentAdapter} is the only concrete port implementation,
 * Spring injects this decorator everywhere {@code PaymentGatewayPort} is required and hands
 * the decorator the real adapter as its delegate — no caller edits.</p>
 *
 * <ul>
 *   <li><b>createPayment</b> — screen (flow classified from metadata); a fraud REVIEW (or a
 *       downgraded server-rail BLOCK) forces {@code captureMethod=manual} and records a
 *       capture hold against the returned payment id.
 *   <li><b>confirmPayment</b> — re-screen ONLY when a new payment method is supplied at
 *       confirm (the BIN/instrument the create-time screen never saw); otherwise pass through.
 *   <li><b>capturePayment</b> — enforcement point: refuse capture while the payment is HELD.
 *   <li><b>voidPayment / getPayment / refunds</b> — pass through (void is free pre-capture
 *       compensation; reads/refunds are not money-out screening points).
 * </ul>
 */
@Primary
@Component
public class GatedPaymentGateway implements PaymentGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(GatedPaymentGateway.class);

    private final PaymentGatewayPort delegate;
    private final PreAuthorizationGate gate;
    private final CaptureHoldService captureHolds;

    public GatedPaymentGateway(HyperSwitchPaymentAdapter delegate,
                               PreAuthorizationGate gate,
                               CaptureHoldService captureHolds) {
        this.delegate = delegate;
        this.gate = gate;
        this.captureHolds = captureHolds;
    }

    @Override
    public PaymentResponse createPayment(PaymentRequest request) {
        ScreeningMode mode = ScreeningMode.fromMetadata(request.metadata());
        String tenantId = tenantFrom(request.metadata());
        GateSignals signals = GateSignals.fromRequest(request.metadata(), request.paymentMethodData());

        GateDecision decision = gate.evaluate(request.idempotencyKey(), request, tenantId, signals, mode);

        PaymentRequest effective = decision.holdCapture()
                ? request.withCaptureMethod("manual")
                : request;
        PaymentResponse response = delegate.createPayment(effective);

        if (decision.holdCapture() && response != null && response.gatewayPaymentId() != null) {
            captureHolds.hold(response.gatewayPaymentId(), tenantId, decision.fraudAssessmentId());
        }
        return response;
    }

    @Override
    public PaymentResponse confirmPayment(String paymentId, ConfirmRequest request) {
        // Re-screen only when confirm supplies a NEW payment method — the instrument the
        // create-time screen never saw. Finalizing an already-screened intent just passes through.
        if (request != null && request.paymentMethodData() != null && !request.paymentMethodData().isBlank()) {
            PaymentResponse existing = delegate.getPayment(paymentId);
            String tenantId = tenantFrom(existing.metadata());
            ScreeningMode mode = ScreeningMode.fromMetadata(existing.metadata());
            GateSignals signals = GateSignals.fromRequest(existing.metadata(), request.paymentMethodData());
            // Reconstruct the gate context from the existing payment + the new method.
            PaymentRequest ctx = new PaymentRequest(
                    existing.amount(), existing.currency(), existing.customerId(),
                    request.paymentMethodType(), request.paymentMethodData(), request.returnUrl(),
                    null, existing.captureMethod() != null ? existing.captureMethod() : "automatic",
                    request.idempotencyKey(), existing.metadata());

            GateDecision decision = gate.evaluate(paymentId, ctx, tenantId, signals, mode);
            PaymentResponse response = delegate.confirmPayment(paymentId, request);
            if (decision.holdCapture() && response != null) {
                captureHolds.hold(paymentId, tenantId, decision.fraudAssessmentId());
            }
            return response;
        }
        return delegate.confirmPayment(paymentId, request);
    }

    @Override
    public PaymentResponse capturePayment(String paymentId, CaptureRequest request) {
        if (captureHolds.isHeld(paymentId)) {
            log.warn("Capture refused for payment {} — held pending fraud review", paymentId);
            throw new PaymentException("Capture blocked pending fraud review", "capture_hold_review");
        }
        return delegate.capturePayment(paymentId, request);
    }

    @Override
    public PaymentResponse voidPayment(String paymentId, VoidRequest request) {
        return delegate.voidPayment(paymentId, request); // free pre-capture compensation — no screen
    }

    @Override
    public PaymentResponse getPayment(String paymentId) {
        return delegate.getPayment(paymentId);
    }

    @Override
    public RefundResponse createRefund(RefundRequest request) {
        return delegate.createRefund(request);
    }

    @Override
    public RefundResponse getRefund(String refundId) {
        return delegate.getRefund(refundId);
    }

    private static String tenantFrom(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object t = metadata.get("tenant_id");
        return t != null ? t.toString() : null;
    }
}
