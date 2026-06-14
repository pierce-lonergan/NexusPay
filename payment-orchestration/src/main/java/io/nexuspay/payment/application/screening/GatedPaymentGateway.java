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

import java.util.HashMap;
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

    /** Authority markers the gate now OWNS — never sourced from client metadata (B-029). */
    private static final String META_SOURCE = "source";
    private static final String META_WORKFLOW = "workflow";
    private static final String META_TENANT_ID = "tenant_id";

    private final PaymentGatewayPort delegate;
    private final PreAuthorizationGate gate;
    private final CaptureHoldService captureHolds;
    private final ScreeningOriginService screeningOrigins;

    public GatedPaymentGateway(HyperSwitchPaymentAdapter delegate,
                               PreAuthorizationGate gate,
                               CaptureHoldService captureHolds,
                               ScreeningOriginService screeningOrigins) {
        this.delegate = delegate;
        this.gate = gate;
        this.captureHolds = captureHolds;
        this.screeningOrigins = screeningOrigins;
    }

    /**
     * Transitional 1-arg path: NO trusted {@link CallContext} was supplied. SHOULD_FIX B: this path
     * must NEVER let client metadata pick a softer rail or a tenant. It used to derive the mode via
     * {@code ScreeningMode.fromMetadata(metadata)} and the tenant via {@code tenantFrom(metadata)} —
     * a forged-authority footgun (a client-shaped {@code source}/{@code tenant_id} could dodge the
     * INTERACTIVE capture-hold or fragment fraud velocity). It now delegates to the trusted create
     * path with {@link CallContext#strictDefault} (INTERACTIVE + null tenant), so the gate takes the
     * STRICTEST rail and no metadata tenant; the {@link #createPayment(PaymentRequest, CallContext)}
     * overload then scrubs the authority markers from the metadata too. Loud, not silent — any
     * un-migrated caller is warned. New callers MUST use the CallContext overload.
     */
    @Override
    public PaymentResponse createPayment(PaymentRequest request) {
        log.warn("createPayment called WITHOUT a trusted CallContext (ref={}) — using the STRICT "
                + "fallback (INTERACTIVE rail + null tenant); client metadata can NOT pick the rail "
                + "or tenant (B-029/SHOULD_FIX B: migrate caller to pass CallContext)",
                request.idempotencyKey());
        return createPayment(request, CallContext.strictDefault(null));
    }

    /**
     * B-029 trusted path: the screening mode + tenant come from the server-set {@link CallContext},
     * never from client-shaped request metadata. Any client-supplied
     * {@code source}/{@code workflow}/{@code tenant_id} marker is advisory and is stripped before
     * the request is forwarded to the PSP, so it can neither pick the rail nor be persisted as
     * authority.
     */
    @Override
    public PaymentResponse createPayment(PaymentRequest request, CallContext ctx) {
        CallContext c = ctx != null ? ctx : CallContext.strictDefault(null);
        assertNoClientAuthority(request.metadata());
        PaymentRequest scrubbed = scrubAuthorityMarkers(request);
        return doCreate(scrubbed, c.tenantId(), c.mode());
    }

    private PaymentResponse doCreate(PaymentRequest request, String tenantId, ScreeningMode mode) {
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("createPayment (ref={}, mode={}) has no trusted tenant — geography resolver "
                    + "will fail closed to a mandatory review", request.idempotencyKey(), mode);
        }
        GateSignals signals = GateSignals.fromRequest(request.metadata(), request.paymentMethodData());

        GateDecision decision = gate.evaluate(request.idempotencyKey(), request, tenantId, signals, mode);

        // Hold capture for an interactive fraud REVIEW. A server-initiated recurring/workflow charge
        // is a pre-authorized mandate: a FRAUD REVIEW records the assessment and is logged for
        // analyst follow-up, but the charge still captures — holding it would surface as
        // requires_capture and trip billing's dunning (the M1 regression).
        //
        // FIX 3 (OFAC blind spot): a MANDATORY COMPLIANCE REVIEW (unknown server-authoritative
        // geography on a cross-border-capable flow) MUST hold capture on EVERY rail, including
        // server rails — otherwise the unknown-geo hold was silently discarded on
        // SERVER_RECURRING/SERVER_OTHER and the charge captured clean. mandatoryReview is set ONLY
        // for the compliance geo branch, so the M1 server-rail fraud-dunning policy is preserved.
        boolean hold = (decision.holdCapture() && mode == ScreeningMode.INTERACTIVE)
                || decision.mandatoryReview();
        if (decision.mandatoryReview() && mode != ScreeningMode.INTERACTIVE) {
            log.warn("Server-rail payment (ref={}, mode={}) held for MANDATORY COMPLIANCE REVIEW "
                    + "(unknown geography) — not captured clean", request.idempotencyKey(), mode);
        } else if (decision.holdCapture() && !decision.mandatoryReview() && mode != ScreeningMode.INTERACTIVE) {
            log.warn("Server-rail payment (ref={}, mode={}) flagged {} by fraud — capturing + recording for review",
                    request.idempotencyKey(), mode, decision.fraudDecision());
        }

        PaymentRequest effective = hold ? request.withCaptureMethod("manual") : request;
        PaymentResponse response = delegate.createPayment(effective);

        if (response != null && response.gatewayPaymentId() != null) {
            // B-029: persist the TRUSTED originating (tenant, mode) so confirm re-screens with the
            // same authority instead of re-deriving it from the (tamperable) intent metadata blob.
            screeningOrigins.record(response.gatewayPaymentId(), new CallContext(tenantId, mode));
            if (hold) {
                captureHolds.hold(response.gatewayPaymentId(), tenantId, decision.fraudAssessmentId());
            }
        }
        return response;
    }

    /**
     * Transitional 2-arg confirm: no trusted {@link CallContext} asserted by the caller. Authority
     * still comes from the server-owned origin store (B-029), NOT the intent metadata; the origin
     * store is the source of truth regardless of which overload is used.
     */
    @Override
    public PaymentResponse confirmPayment(String paymentId, ConfirmRequest request) {
        return doConfirm(paymentId, request);
    }

    /**
     * B-029 confirm: the trusted {@link CallContext} is asserted by the caller (e.g. REST passes the
     * principal's tenant). The server-owned origin store remains the authority for {@code (tenant,
     * mode)}; the supplied ctx is accepted but does not let a client re-classify the rail.
     */
    @Override
    public PaymentResponse confirmPayment(String paymentId, ConfirmRequest request, CallContext ctx) {
        return doConfirm(paymentId, request);
    }

    private PaymentResponse doConfirm(String paymentId, ConfirmRequest request) {
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

        // B-029: authority comes from the SERVER-OWNED origin store, never from existing.metadata()
        // (a free-form blob that could be re-classified). A legacy intent with no origin row falls
        // back to the strictest rail (INTERACTIVE) + no tenant — never to the client metadata.
        ScreeningOriginService.Origin origin = screeningOrigins.find(paymentId)
                .orElse(null);
        String tenantId;
        ScreeningMode mode;
        if (origin != null) {
            tenantId = origin.tenantId();
            mode = origin.mode();
        } else {
            tenantId = null;
            mode = ScreeningMode.INTERACTIVE;
            log.warn("confirmPayment {} has no server-owned screening origin — falling back to strict "
                    + "INTERACTIVE rail + null tenant (NOT re-deriving from intent metadata)", paymentId);
        }
        GateSignals signals = GateSignals.fromRequest(existing.metadata(),
                request != null ? request.paymentMethodData() : null);
        PaymentRequest ctx = reconstructForScreening(existing, request);

        // Throws cross_border_blocked (sanctions, all modes) / fraud_blocked (interactive BLOCK)
        // BEFORE the PSP confirm.
        GateDecision decision = gate.evaluate(paymentId, ctx, tenantId, signals, mode);

        // FIX 3: a MANDATORY COMPLIANCE REVIEW (unknown geography) holds on ALL rails — including
        // server rails — so the OFAC review cannot be skipped at confirm. An ordinary fraud REVIEW
        // still holds only on the interactive rail (server-rail fraud REVIEW proceeds, M1 policy).
        boolean hold = (decision.holdCapture() && mode == ScreeningMode.INTERACTIVE)
                || decision.mandatoryReview();
        if (hold) {
            captureHolds.hold(paymentId, tenantId, decision.fraudAssessmentId());
            // Capture cannot be retroactively held on an auto-capture intent at confirm time, so
            // refuse rather than let the PSP capture a flagged payment (B1). A manual-capture intent
            // confirms safely (authorizes only; capture stays HELD, enforced at capturePayment).
            if (!"manual".equalsIgnoreCase(existing.captureMethod())) {
                String code = decision.mandatoryReview() ? "compliance_review_hold" : "fraud_review_hold";
                String reason = decision.mandatoryReview()
                        ? "Payment flagged for compliance review (geography); confirm of an auto-capture intent is not permitted"
                        : "Payment flagged for review; confirm of an auto-capture intent is not permitted";
                throw new PaymentException(reason, code);
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

    /**
     * Defense-in-depth (B-029): when a TRUSTED {@link CallContext} was supplied, any client-supplied
     * authority marker in the metadata is a forged-authority attempt — it cannot take effect (the
     * gate already took mode+tenant from the ctx), but we log it so the attempt is visible.
     */
    private static void assertNoClientAuthority(Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        if (metadata.containsKey(META_SOURCE) || metadata.containsKey(META_WORKFLOW)
                || metadata.containsKey(META_TENANT_ID)) {
            log.warn("Client-supplied authority marker(s) present in request metadata "
                    + "(source/workflow/tenant_id) while a trusted CallContext was supplied — "
                    + "IGNORED (advisory only, B-029)");
        }
    }

    /**
     * Returns a copy of the request with the authority markers the gate now OWNS stripped from the
     * metadata, so a client-supplied {@code source}/{@code workflow}/{@code tenant_id} can neither
     * leak downstream to the PSP nor be persisted as authority. {@code ip_country_trusted} is left
     * untouched (B-025 owns the server-stamped geography key).
     */
    private static PaymentRequest scrubAuthorityMarkers(PaymentRequest request) {
        Map<String, Object> metadata = request.metadata();
        if (metadata == null
                || !(metadata.containsKey(META_SOURCE) || metadata.containsKey(META_WORKFLOW)
                        || metadata.containsKey(META_TENANT_ID))) {
            return request;
        }
        Map<String, Object> scrubbed = new HashMap<>(metadata);
        scrubbed.remove(META_SOURCE);
        scrubbed.remove(META_WORKFLOW);
        scrubbed.remove(META_TENANT_ID);
        return new PaymentRequest(request.amount(), request.currency(), request.customerId(),
                request.paymentMethodType(), request.paymentMethodData(), request.returnUrl(),
                request.description(), request.captureMethod(), request.idempotencyKey(), scrubbed);
    }
}
