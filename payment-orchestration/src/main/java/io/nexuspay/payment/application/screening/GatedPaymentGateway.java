package io.nexuspay.payment.application.screening;

import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.common.mode.PaymentMode;
import io.nexuspay.payment.adapter.out.hyperswitch.HyperSwitchPaymentAdapter;
import io.nexuspay.payment.adapter.out.mock.MockPaymentGatewayPort;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.service.clock.TestClockService;
import io.nexuspay.payment.application.service.projection.PaymentProjectionService;
import io.nexuspay.payment.application.webhook.MockWebhookSynthesizer;
import io.nexuspay.payment.application.webhook.WebhookMetadataService;
import io.nexuspay.payment.domain.CaptureRequest;
import io.nexuspay.payment.domain.ConfirmRequest;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.RefundResponse;
import io.nexuspay.payment.domain.VoidRequest;
import io.nexuspay.payment.domain.event.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Instant;
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
    /** INT-3: deterministic in-memory fake PSP used for TEST-mode (sk_test_) payments. */
    private final MockPaymentGatewayPort mockDelegate;
    private final PreAuthorizationGate gate;
    private final CaptureHoldService captureHolds;
    private final ScreeningOriginService screeningOrigins;
    private final WebhookMetadataService webhookMetadata;
    /** INT-3: writes the same OutboxEvent shape as the real webhook controller for terminal mock ops. */
    private final MockWebhookSynthesizer mockWebhookSynthesizer;
    /**
     * GAP-076 (critique v3 F1): BEST-EFFORT read-model writer. Every projection upsert below is swallowed
     * by this service (try/catch/log) and runs in its own REQUIRES_NEW tx, so a projection write can NEVER
     * fail/block/rollback a payment/refund op (the CARDINAL RULE). The projection is never read here.
     */
    private final PaymentProjectionService projection;
    /**
     * GAP-078 (critique v3 F5): the per-tenant TEST CLOCK. Consulted ONLY inside the {@code routeToMock}
     * (mock/test rail) branches below to re-stamp the mock-synthesized {@code createdAt} on TEST-created
     * payment/refund artifacts with the tenant's frozen instant. It is NEVER consulted on a live delegate
     * branch — so a LIVE charge's timestamp is physically unable to be touched by it (LIVE ISOLATION). It
     * controls ONLY that createdAt (see {@link TestClockService}'s non-scope javadoc); not expiry/TTL/retry.
     */
    private final TestClockService testClock;

    public GatedPaymentGateway(HyperSwitchPaymentAdapter delegate,
                               MockPaymentGatewayPort mockDelegate,
                               PreAuthorizationGate gate,
                               CaptureHoldService captureHolds,
                               ScreeningOriginService screeningOrigins,
                               WebhookMetadataService webhookMetadata,
                               MockWebhookSynthesizer mockWebhookSynthesizer,
                               PaymentProjectionService projection,
                               TestClockService testClock) {
        this.delegate = delegate;
        this.mockDelegate = mockDelegate;
        this.gate = gate;
        this.captureHolds = captureHolds;
        this.screeningOrigins = screeningOrigins;
        this.webhookMetadata = webhookMetadata;
        this.mockWebhookSynthesizer = mockWebhookSynthesizer;
        this.projection = projection;
        this.testClock = testClock;
    }

    /**
     * INT-3: the ONE place the mock-vs-real routing decision is made, evaluated on EVERY port call (not
     * gated on any dev profile — it is key-mode routing). The fail-closed direction differs by execution
     * context:
     * <ul>
     *   <li>request that affirmatively resolved a TEST key → mock;</li>
     *   <li>request that affirmatively resolved a LIVE key → real PSP (a determinable LIVE key ALWAYS
     *       reaches HyperSwitch — the explicit invariant carve-out);</li>
     *   <li>UNSET on a servlet REQUEST thread (an unauthenticated/edge path that still reached a payment
     *       op) → mock (FAIL CLOSED — never risk a real charge); and</li>
     *   <li>UNSET on a system/consumer/scheduler thread (no servlet request) → real PSP — system threads
     *       are real, never test.</li>
     * </ul>
     */
    private boolean routeToMock() {
        if (PaymentMode.isTestExplicit()) {
            return true;
        }
        if (PaymentMode.isLiveExplicit()) {
            return false;
        }
        // UNSET: a request thread fails closed to the mock; a system/consumer thread resolves to the
        // real PSP. The two are distinguished by whether a servlet request is bound to this thread.
        return isRequestThread();
    }

    /**
     * DX-5a (MONEY-SAFETY): mock-vs-real routing that consults the CallContext's DURABLE mode FIRST,
     * before the request/system-thread {@code PaymentMode} heuristic. This closes the hole on a
     * server-initiated charge whose execution thread carries no request-scoped {@code PaymentMode}:
     * the {@code @Scheduled @SystemTransactional} renewal/dunning jobs run on a SYSTEM thread, where
     * {@link #routeToMock()} alone resolves LIVE (system threads are real) — so a recurring charge
     * for a TEST subscription would have hit the REAL PSP. Billing now threads the subscription's
     * durable {@code is_live} into {@code ctx.live()}.
     *
     * <ul>
     *   <li>{@code ctxLive == Boolean.TRUE} → real PSP (a LIVE subscription always reaches HyperSwitch);</li>
     *   <li>{@code ctxLive == Boolean.FALSE} → mock (a TEST subscription NEVER reaches the real PSP —
     *       the CHARTER guarantee — even on a system thread with {@code PaymentMode} unset);</li>
     *   <li>{@code ctxLive == null} → INDETERMINATE: defer to the existing {@link #routeToMock()}
     *       heuristic, byte-for-byte unchanged. The UNSET-system-thread invariant (no declared mode ⇒
     *       real) is preserved; the fix is that billing now DECLARES the mode rather than leaving it
     *       null.</li>
     * </ul>
     */
    private boolean routeToMock(Boolean ctxLive) {
        if (ctxLive != null) {
            return !ctxLive; // TRUE -> real (false), FALSE -> mock (true)
        }
        return routeToMock();
    }

    /** True when a servlet request is bound to the current thread (vs. a Kafka/scheduler/system thread). */
    private static boolean isRequestThread() {
        return RequestContextHolder.getRequestAttributes() != null;
    }

    /**
     * INT-3 defense-in-depth fail-safe for the DEFERRED refund path. An above-threshold refund of a TEST
     * payment is not executed on the originating request thread — it is deferred to a maker-checker
     * approval that later runs on the APPROVER's thread (a Keycloak/OIDC console actor defaults to LIVE)
     * or the {@code RefundReconciler}'s SYSTEM thread (mode UNSET → resolves LIVE). On EITHER thread the
     * originating test key's {@code PaymentMode} is gone, so {@link #routeToMock()} alone would send a
     * {@code pay_test_*} refund to HyperSwitch.
     *
     * <p>A {@code pay_test_}/{@code re_test_} id is, by construction, a MOCK artifact the real PSP never
     * minted (the real adapter uses opaque connector ids). So a refund/read whose target id carries the
     * test prefix MUST route to the mock regardless of the executing thread's mode. This makes the
     * guarantee depend on the server-minted id itself, not solely on the request-scoped holder.</p>
     */
    private static boolean isTestModeId(String id) {
        return id != null
                && (id.startsWith(MockPaymentGatewayPort.PAY_PREFIX)
                        || id.startsWith(MockPaymentGatewayPort.REFUND_PREFIX));
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
        // INT-1: capture the client-supplied merchant correlation metadata BEFORE scrubbing the
        // authority markers, so the outbound-webhook store keeps correlation keys (userId/packId/...)
        // while the authority markers (source/workflow/tenant_id) are dropped by sanitize() on persist.
        Map<String, Object> merchantMeta = request.metadata();
        PaymentRequest scrubbed = scrubAuthorityMarkers(request);
        return doCreate(scrubbed, c.tenantId(), c.mode(), c.live(), merchantMeta);
    }

    private PaymentResponse doCreate(PaymentRequest request, String tenantId, ScreeningMode mode,
                                     Boolean ctxLive, Map<String, Object> merchantMeta) {
        // INT-3: a TEST key routes the actual create to the deterministic mock — NEVER HyperSwitch, in
        // EVERY profile. We still record the screening ORIGIN (trusted tenant+mode) and the INT-1 V4030
        // metadata so a test webhook round-trips the real tenant + userId/packId — but we SKIP the
        // fraud/sanctions GATE (it is a money-out control; the mock moves no real money, hits no PSP).
        // DX-5a: createPayment has no payment id yet (no isTestModeId fail-safe is possible at create),
        // so routing MUST consult the durable ctx.live() first — a TEST subscription's renewal/dunning
        // charge runs on a SYSTEM thread with PaymentMode unset, where routeToMock() alone resolves
        // LIVE. ctx.live()==FALSE forces the mock; ==null preserves the pre-DX-5a heuristic.
        if (routeToMock(ctxLive)) {
            PaymentResponse response = mockDelegate.createPayment(request);
            // GAP-078: re-stamp the mock-synthesized createdAt with the per-tenant TEST CLOCK's frozen
            // instant. This is the ONE consult point right after the mock returns, so it covers EVERY mock
            // outcome (forced FAILURE, requires_action/processing/fraud_hold, and success). withCreatedAt
            // preserves nextAction (full 13-arg copy). The re-stamped `response` then flows into the
            // origin/metadata/synthesizer/projection writes below, so the GAP-076 projection's created_at
            // (taken FROM response.createdAt) is the frozen instant and the list orders by it. A null/blank
            // tenant -> nowFor falls back to Instant.now(), making the no-trusted-tenant path byte-identical
            // to before. This is a MOCK-ONLY branch; the live delegate branch never reaches here (LIVE
            // ISOLATION). It controls ONLY createdAt — NOT mandate expiry/idempotency TTL/retry/updated_at.
            if (response != null && tenantId != null && !tenantId.isBlank()) {
                Instant frozen = testClock.nowFor(tenantId);
                response = response.withCreatedAt(frozen);
                // SHOULD_FIX (read-path consistency): also re-stamp the MOCK STORE so a later single-retrieve
                // GET /v1/payments/{id} (served from the mock store, not the projection) returns the SAME
                // createdAt as this response + the GAP-076 list — no undocumented real-time split. Mock-rail
                // only (the mock holds no live artifact), so this can never touch a live timestamp.
                mockDelegate.restampCreatedAt(response.gatewayPaymentId(), frozen);
            }
            if (response != null && response.gatewayPaymentId() != null) {
                screeningOrigins.record(response.gatewayPaymentId(), new CallContext(tenantId, mode));
                // live=false: the V4030 __livemode marker drives the delivered webhook's top-level livemode.
                webhookMetadata.record(response.gatewayPaymentId(), tenantId, merchantMeta, false);
                // Ordering guarantee: synthesize the outbox event ONLY AFTER origin+metadata are persisted,
                // so the 1s OutboxRelay poll cannot deliver before the metadata row exists (which would
                // ship {} metadata + a "default" tenant). An auto-capture create is a terminal money state.
                if (response.isSuccessful()) {
                    mockWebhookSynthesizer.onTerminal(response, tenantId, PaymentEvent.PAYMENT_CAPTURED);
                } else if (response.isFailed()) {
                    // TEST-1: a forced __test_outcome decline is a terminal FAILED state — synthesize the
                    // canonical payment.failed webhook so an integrator can exercise failure handling. This
                    // is reachable ONLY here (routeToMock true / TEST key); a real charge is never affected.
                    mockWebhookSynthesizer.onTerminalFailure(response, tenantId, PaymentEvent.PAYMENT_FAILED);
                }
            }
            // GAP-076: best-effort read-model birth state (incl. requires_action/processing/requires_capture)
            // for read-your-write on GET /v1/payments. Mock branch -> livemode=false (a test key's rows).
            // Swallowed inside the service; can never fail the create.
            projection.record(response, tenantId, false);
            return response;
        }

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
            // INT-1: persist the merchant correlation metadata for outbound-webhook enrichment, keyed by
            // the gateway payment id under the SERVER-DERIVED trusted tenant (never the client echo). This
            // is the single universal chokepoint — REST /v1/payments, billing, workflow, and SDK/session
            // checkout all create their intent here — so it covers every webhook-emitting payment. The
            // store sanitizes (PAN/card + source/workflow/tenant_id stripped) and caps size; a persist
            // failure NEVER fails the payment (delivery just sends {} metadata).
            // INT-3: this is the LIVE path (routeToMock() was false) -> stamp __livemode=true so the
            // delivered webhook's top-level livemode is server-sourced for real payments too.
            webhookMetadata.record(response.gatewayPaymentId(), tenantId, merchantMeta, true);
            if (hold) {
                captureHolds.hold(response.gatewayPaymentId(), tenantId, decision.fraudAssessmentId());
            }
            // GAP-076: best-effort read-model birth state for the LIVE path -> livemode=true. A live create
            // that returns requires_action/processing is captured here (the outbox only carries TERMINAL
            // events, so an event-only projection would miss it). Swallowed; never fails the create.
            projection.record(response, tenantId, true);
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
        // INT-3: a TEST key confirms against the mock and SKIPS the re-screen (no money-out, no PSP). The
        // id fail-safe also routes a pay_test_* confirm to the mock on any non-test-request thread.
        if (routeToMock() || isTestModeId(paymentId)) {
            PaymentResponse mockResp = mockDelegate.confirmPayment(paymentId, request);
            // GAP-076: best-effort read-model update (captures requires_capture-after-confirm). Mock branch
            // -> livemode=false; tenant from the server-owned origin store keyed by the payment id.
            projection.record(mockResp, resolveTenant(paymentId), false);
            return mockResp;
        }
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
        PaymentResponse confirmResp = delegate.confirmPayment(paymentId, request);
        // GAP-076: best-effort read-model update on the LIVE confirm -> livemode=true. Captures
        // requires_capture-after-confirm (no outbox event fires for it). tenant from the origin store.
        projection.record(confirmResp, tenantId, true);
        return confirmResp;
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
        // INT-3: a TEST key captures against the mock — NEVER the HyperSwitch delegate or the capture-hold
        // (the hold is a live fraud-review control). Capture is a terminal money state -> synthesize
        // PaymentCaptured (payment.succeeded) so the test webhook loop fires. The id fail-safe also routes
        // a pay_test_* capture to the mock on a non-test-request thread.
        if (routeToMock() || isTestModeId(paymentId)) {
            PaymentResponse r = mockDelegate.capturePayment(paymentId, request);
            String tenant = resolveTenant(paymentId);
            mockWebhookSynthesizer.onTerminal(r, tenant, PaymentEvent.PAYMENT_CAPTURED);
            // GAP-076: best-effort read-model update (requires_capture -> succeeded). Mock -> livemode=false.
            projection.record(r, tenant, false);
            return r;
        }
        if (captureHolds.isHeld(paymentId)) {
            log.warn("Capture refused for payment {} — held pending fraud review", paymentId);
            throw new PaymentException("Capture blocked pending fraud review", "capture_hold_review");
        }
        PaymentResponse r = delegate.capturePayment(paymentId, request);
        // GAP-076: best-effort read-model update on the LIVE capture -> livemode=true.
        projection.record(r, resolveTenant(paymentId), true);
        return r;
    }

    @Override
    public PaymentResponse voidPayment(String paymentId, VoidRequest request) {
        // INT-3: a TEST key voids against the mock. Cancelled is not money-out, but synthesize
        // PaymentVoided (payment.canceled) for loop fidelity with the real path. The id fail-safe also
        // routes a pay_test_* void to the mock on a non-test-request thread.
        if (routeToMock() || isTestModeId(paymentId)) {
            PaymentResponse r = mockDelegate.voidPayment(paymentId, request);
            String tenant = resolveTenant(paymentId);
            mockWebhookSynthesizer.onTerminal(r, tenant, PaymentEvent.PAYMENT_VOIDED);
            // GAP-076: best-effort read-model update (-> cancelled). Mock -> livemode=false.
            projection.record(r, tenant, false);
            return r;
        }
        PaymentResponse r = delegate.voidPayment(paymentId, request); // free pre-capture compensation — no screen
        // GAP-076: best-effort read-model update on the LIVE void -> livemode=true.
        projection.record(r, resolveTenant(paymentId), true);
        return r;
    }

    @Override
    public PaymentResponse getPayment(String paymentId) {
        // INT-3: a TEST key reads from the mock store — never a network call to HyperSwitch. The id
        // fail-safe also reads a pay_test_* id from the mock on a non-test-request thread.
        if (routeToMock() || isTestModeId(paymentId)) {
            return mockDelegate.getPayment(paymentId);
        }
        return delegate.getPayment(paymentId);
    }

    @Override
    public RefundResponse createRefund(RefundRequest request) {
        // INT-3: a TEST key refunds against the mock and synthesizes RefundCompleted (payment.refunded);
        // the originating test payment's origin row supplies the trusted tenant.
        //
        // DEFERRED-PATH FAIL-SAFE (BLOCKER): an above-threshold refund is executed later by the approver
        // (console actor → LIVE) or the RefundReconciler (system thread → LIVE), NOT on the originating
        // test request thread — so routeToMock() alone is gone by then. We ALSO route to the mock when the
        // TARGET PAYMENT id is a mock artifact (pay_test_*), so a test payment's refund can NEVER reach
        // HyperSwitch through the maker-checker/reconciler path. fail-safe || thread-mode.
        if (routeToMock() || isTestModeId(request != null ? request.paymentId() : null)) {
            RefundResponse r = mockDelegate.createRefund(request);
            String tenant = resolveTenant(request.paymentId());
            // GAP-078: re-stamp the mock refund's createdAt with the per-tenant TEST CLOCK's frozen instant
            // (covers BOTH the forced-failure and the success branch below). The re-stamped `r` flows into
            // recordRefund, so the GAP-076 refund projection inherits the frozen created_at. tenant comes
            // from the server-owned origin store (resolveTenant), never client input — no authority widening.
            // MOCK-ONLY branch; the live delegate refund never reaches here (LIVE ISOLATION). createdAt only.
            if (r != null && tenant != null && !tenant.isBlank()) {
                Instant frozen = testClock.nowFor(tenant);
                r = r.withCreatedAt(frozen);
                // SHOULD_FIX (read-path consistency): re-stamp the MOCK STORE so a later single-retrieve
                // GET /v1/refunds/{id} (served from the mock store) agrees with this response + the GAP-076
                // list. Mock-rail only — never touches a live refund's timestamp.
                mockDelegate.restampCreatedAt(r.gatewayRefundId(), frozen);
            }
            if (r != null && !r.isSuccessful()) {
                // TEST-1: a forced refund failure (magic-amount sentinel) -> canonical payment.refund.failed.
                // Mock-only path; no real refund is ever skipped/cancelled by this.
                mockWebhookSynthesizer.onRefundFailed(r, tenant, PaymentEvent.REFUND_FAILED);
            } else {
                mockWebhookSynthesizer.onRefundTerminal(r, tenant, PaymentEvent.REFUND_COMPLETED);
            }
            // GAP-076: best-effort refund read-model birth state. Mock -> livemode=false.
            projection.recordRefund(r, tenant, false);
            return r;
        }
        RefundResponse r = delegate.createRefund(request);
        // GAP-076: best-effort refund read-model on the LIVE path -> livemode=true. tenant from the
        // origin store keyed by the parent payment id (the same trusted source the webhook uses).
        projection.recordRefund(r, resolveTenant(request.paymentId()), true);
        return r;
    }

    @Override
    public RefundResponse getRefund(String refundId) {
        // INT-3: a TEST key reads the refund from the mock store — never a network call. The id fail-safe
        // covers a deferred/system reader: a re_test_* id is a mock artifact, so read it from the mock.
        if (routeToMock() || isTestModeId(refundId)) {
            return mockDelegate.getRefund(refundId);
        }
        return delegate.getRefund(refundId);
    }

    /**
     * INT-3: resolves the TRUSTED tenant for a synthesized test webhook from the server-owned screening
     * origin store keyed by the payment id — the SAME source the real {@code HyperSwitchWebhookController}
     * uses, never client metadata. Returns {@code null} when absent (the synthesizer then falls back to
     * "default" — a delivery gap, never a cross-tenant leak).
     */
    private String resolveTenant(String paymentId) {
        return screeningOrigins.find(paymentId)
                .map(ScreeningOriginService.Origin::tenantId)
                .orElse(null);
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
        // TEST-3c: preserve the off-session fields (paymentMethod/offSession/setupFutureUsage/mandateId)
        // when only the metadata is scrubbed — otherwise an off-session charge that carries an authority
        // marker would silently lose its credentialRef/off_session hints. null for inline-card requests.
        return new PaymentRequest(request.amount(), request.currency(), request.customerId(),
                request.paymentMethodType(), request.paymentMethodData(), request.returnUrl(),
                request.description(), request.captureMethod(), request.idempotencyKey(), scrubbed,
                request.paymentMethod(), request.offSession(), request.setupFutureUsage(),
                request.mandateId());
    }
}
