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

        // Hold capture ONLY for interactive flows. A server-initiated recurring/workflow charge
        // is a pre-authorized mandate: a fraud REVIEW records the assessment and is logged for
        // analyst follow-up, but the charge still captures — holding it would surface as
        // requires_capture and trip billing's dunning (the M1 regression).
        boolean hold = decision.holdCapture() && mode == ScreeningMode.INTERACTIVE;
        if (decision.holdCapture() && mode != ScreeningMode.INTERACTIVE) {
            log.warn("Server-rail payment (ref={}, mode={}) flagged {} by fraud — capturing + recording for review",
                    request.idempotencyKey(), mode, decision.fraudDecision());
        }

        PaymentRequest effective = hold ? request.withCaptureMethod("manual") : request;
        PaymentResponse response = delegate.createPayment(effective);

        if (hold && response != null && response.gatewayPaymentId() != null) {
            captureHolds.hold(response.gatewayPaymentId(), tenantId, decision.fraudAssessmentId());
        }
        return response;
    }

    @Override
    public PaymentResponse confirmPayment(String paymentId, ConfirmRequest request) {
        // ALWAYS screen on confirm (B2): sanctions is a hard block in every mode, and confirm is
        // the first point a real instrument may appear (a server intent created with no PM/BIN).
        // Load the intent defensively (M3): getPayment is circuit-broken with no fallback.
        PaymentResponse existing;
        try {
            existing = delegate.getPayment(paymentId);
        } catch (PaymentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PaymentException("Unable to load payment for screening: " + paymentId, "payment_screen_unavailable");
        }

        String tenantId = tenantFrom(existing.metadata());
        ScreeningMode mode = ScreeningMode.fromMetadata(existing.metadata());
        GateSignals signals = GateSignals.fromRequest(existing.metadata(),
                request != null ? request.paymentMethodData() : null);
        PaymentRequest ctx = reconstructForScreening(existing, request);

        // Throws cross_border_blocked (sanctions, all modes) / fraud_blocked (interactive BLOCK)
        // BEFORE the PSP confirm.
        GateDecision decision = gate.evaluate(paymentId, ctx, tenantId, signals, mode);

        if (decision.holdCapture() && mode == ScreeningMode.INTERACTIVE) {
            captureHolds.hold(paymentId, tenantId, decision.fraudAssessmentId());
            // Capture cannot be retroactively held on an auto-capture intent at confirm time, so
            // refuse rather than let the PSP capture a flagged payment (B1). A manual-capture intent
            // confirms safely (authorizes only; capture stays HELD, enforced at capturePayment).
            if (!"manual".equalsIgnoreCase(existing.captureMethod())) {
                throw new PaymentException(
                        "Payment flagged for review; confirm of an auto-capture intent is not permitted",
                        "fraud_review_hold");
            }
        } else if (decision.holdCapture()) {
            log.warn("Server-rail confirm (payment={}, mode={}) flagged {} by fraud — proceeding",
                    paymentId, mode, decision.fraudDecision());
        }
        return delegate.confirmPayment(paymentId, request);
    }

    /**
     * Build a screenable {@code PaymentRequest} from the existing intent + the confirm method.
     * {@code PaymentRequest} requires amount &gt; 0 and a non-blank currency, but a
     * requires_payment_method/requires_confirmation intent can legitimately carry 0/null (M3) —
     * floor those for the screen (the sanctions decision is country-based, not amount-based).
     */
    private static PaymentRequest reconstructForScreening(PaymentResponse existing, ConfirmRequest request) {
        long amount = existing.amount() > 0 ? existing.amount() : 1;
        String currency = (existing.currency() != null && !existing.currency().isBlank())
                ? existing.currency() : "XXX";
        String capture = (existing.captureMethod() != null && !existing.captureMethod().isBlank())
                ? existing.captureMethod() : "automatic";
        return new PaymentRequest(amount, currency, existing.customerId(),
                request != null ? request.paymentMethodType() : null,
                request != null ? request.paymentMethodData() : null,
                request != null ? request.returnUrl() : null, null,
                capture, request != null ? request.idempotencyKey() : null, existing.metadata());
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
