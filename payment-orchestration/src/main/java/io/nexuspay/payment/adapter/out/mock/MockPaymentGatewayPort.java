package io.nexuspay.payment.adapter.out.mock;

import io.nexuspay.common.exception.PaymentException;
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
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * INT-3: deterministic in-memory fake PSP for TEST-mode ({@code sk_test_}) payments. The
 * {@code @Primary GatedPaymentGateway} routes every payment op to this adapter when the request-scoped
 * {@code PaymentMode} resolves TEST, so a test key NEVER reaches {@link
 * io.nexuspay.payment.adapter.out.hyperswitch.HyperSwitchPaymentAdapter}.
 *
 * <p><b>Hard invariant:</b> this class imports NO {@code HyperSwitch*}, {@code RestClient}, or any HTTP
 * client — it does ZERO network I/O. State lives only in an in-memory map, so behavior is deterministic
 * within a process. (Enforced by {@code MockPaymentGatewayPortArchTest}.)</p>
 *
 * <p>It is a plain {@code @Component} (single no-arg constructor, L-054) and is deliberately NOT
 * {@code @Primary} — the gateway keeps {@code @Primary} and selects this adapter as an alternate
 * delegate. The mock has NO collaborators: webhook synthesis for terminal money states is driven by the
 * gateway AFTER it has persisted the screening origin + INT-1 metadata (so the synthesized outbox event
 * never races the metadata row), keeping this class free of any persistence dependency.</p>
 *
 * <p>Id scheme (Stripe-style test ids): payments {@code pay_test_*}, refunds {@code re_test_*},
 * connector {@code "mock"}, connector txn {@code txn_test_*}.</p>
 *
 * <p><b>TEST-1 forced outcomes (TEST-MODE ONLY).</b> By default every mock create succeeds — an
 * integrator could never exercise decline/failure handling without a real declined card (which the
 * sandbox charter forbids). So {@link #createPayment} honors a reserved, SERVER-CONTROL metadata key
 * {@code __test_outcome} (case-insensitive value): {@code "declined"}, {@code "insufficient_funds"},
 * {@code "expired_card"} force a {@code STATUS_FAILED} response with the matching {@code error_code} +
 * {@code error_message} (single source of truth: {@link ForcedOutcome}); absent / {@code "succeed"} / an
 * UNKNOWN value preserves the existing success behavior byte-for-byte (an unknown value must NEVER fail a
 * happy-path test — it is logged at debug, not honored). {@link #createRefund} forces a failed refund via
 * a documented MAGIC AMOUNT sentinel ({@code amount % 100 == }{@value #REFUND_FAILURE_SENTINEL}), because
 * {@code RefundRequest} carries no metadata map.
 *
 * <p>This mechanism is reachable ONLY through this mock, which is reachable ONLY when {@code
 * GatedPaymentGateway.routeToMock(...)} is true (a TEST key / {@code livemode=false}); an {@code sk_live_}
 * key can NEVER reach the mock. So a forced FAILURE can never cancel/skip a REAL charge or touch
 * HyperSwitch — it moves no real money and does zero network I/O, exactly like every other mock op.</p>
 */
@Component
public class MockPaymentGatewayPort implements PaymentGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGatewayPort.class);

    /** Connector name stamped on every mock response (mirrors HyperSwitch's connector field). */
    public static final String CONNECTOR = "mock";

    /**
     * TEST-1: the reserved, SERVER-CONTROL metadata key an integrator sets on a TEST-mode create to force a
     * deterministic non-success outcome. Like {@code __livemode} it is a server-reserved control key (the
     * {@code __} prefix) — it must NEVER leak into a delivered webhook's {@code data.metadata}
     * ({@code WebhookMetadataService} strips it; see RESERVED-KEY HYGIENE).
     */
    public static final String TEST_OUTCOME_KEY = "__test_outcome";

    /**
     * TEST-1 refund-failure signal. {@code RefundRequest} has no metadata map, so a forced refund FAILURE is
     * signalled by a documented MAGIC AMOUNT: a refund whose minor-units {@code amount % 100 ==} this
     * sentinel forces {@code STATUS_FAILED} + {@code error_code "refund_failed"}. Any other amount keeps the
     * existing {@code STATUS_SUCCEEDED} behavior. Chosen because it is the only deterministic signal already
     * present on {@code RefundRequest} (no new field/collaborator) and is trivially reproducible in a curl.
     */
    public static final int REFUND_FAILURE_SENTINEL = 66;

    /** TEST-1: the forced-refund-failure error code (mirrors a PSP {@code refund_failed}). */
    public static final String REFUND_FAILED_CODE = "refund_failed";

    /**
     * TEST-1 single-source-of-truth mapping for forced PAYMENT failures: outcome string -&gt;
     * ({@code error_code}, {@code error_message}). A {@code null} {@link #lookup(Object)} result means
     * "no forced failure" — keep the existing success/requires_capture behavior.
     */
    private enum ForcedOutcome {
        DECLINED("declined", "card_declined", "Your card was declined."),
        INSUFFICIENT_FUNDS("insufficient_funds", "insufficient_funds", "Your card has insufficient funds."),
        EXPIRED_CARD("expired_card", "expired_card", "Your card has expired.");

        /** A normal (non-failing) sentinel an integrator may pass to be explicit — maps to NO failure. */
        private static final String SUCCEED = "succeed";

        private final String token;
        final String errorCode;
        final String errorMessage;

        ForcedOutcome(String token, String errorCode, String errorMessage) {
            this.token = token;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        /**
         * Resolves a {@code __test_outcome} metadata value to a forced FAILURE, or {@code null} when the
         * value is absent, {@code "succeed"}, or UNKNOWN (a typo must NOT fail a happy-path test — the
         * caller logs at debug and proceeds with the normal success path). Case-insensitive, trimmed.
         */
        static ForcedOutcome lookup(Object raw) {
            if (raw == null) {
                return null;
            }
            String v = raw.toString().trim().toLowerCase(Locale.ROOT);
            if (v.isEmpty() || SUCCEED.equals(v)) {
                return null;
            }
            for (ForcedOutcome o : values()) {
                if (o.token.equals(v)) {
                    return o;
                }
            }
            return null; // UNKNOWN -> no forced failure (back-compat: a typo never breaks a happy path)
        }
    }

    /**
     * TEST-1: the set of recognized {@code __test_outcome} values that force a PAYMENT failure — exposed for
     * the catalog doc + tests (single source of truth, derived from {@link ForcedOutcome}). Does NOT include
     * {@code "succeed"} (which is an explicit no-op) or the refund sentinel (a refund-amount signal).
     */
    public static final Set<String> FORCED_PAYMENT_OUTCOMES = Set.of(
            ForcedOutcome.DECLINED.token,
            ForcedOutcome.INSUFFICIENT_FUNDS.token,
            ForcedOutcome.EXPIRED_CARD.token);

    /**
     * TEST-6 single-source-of-truth mapping for forced NON-FAILURE outcomes: a {@code __test_outcome} value
     * that resolves a successful create into a deterministic non-terminal/manual-capture state so an
     * integrator can exercise SCA/redirect, async-settle, and review-hold→capture flows WITHOUT a real card.
     * Parallel to {@link ForcedOutcome} (which only ever yields {@code STATUS_FAILED}) so the failure enum
     * stays clean. A {@code null} {@link #lookup(Object)} means "no forced non-failure" — keep the existing
     * success/requires_capture default. NONE of these synthesizes a terminal webhook (all are non-terminal or
     * a manual-capture shape; the gateway synthesizes only on success/failed).
     *
     * <p>TEST-MODE ONLY, like every mock outcome: reachable only through this mock, which a live key
     * ({@code sk_live_}) can never reach (see class javadoc). A real payment can NEVER be forced into
     * requires_action / processing / a review hold — no real money moves and no PSP is touched.</p>
     */
    private enum NonFailureOutcome {
        /**
         * A3: 3DS/SCA — STATUS_REQUIRES_ACTION + a {@code next_action} redirect stub (built from the minted
         * pay id; non-terminal, no webhook).
         */
        REQUIRES_ACTION("requires_action", PaymentResponse.STATUS_REQUIRES_ACTION),
        /** A4: async settle — STATUS_PROCESSING, no next_action, no webhook (integrator fires the test event). */
        PROCESSING("processing", PaymentResponse.STATUS_PROCESSING),
        /**
         * A5 (option b): review-hold SIMULATION — STATUS_REQUIRES_CAPTURE (manual-capture shaped), no hold row.
         * Released via the existing capture endpoint. The real PreAuthorizationGate/CaptureHoldService fraud
         * screen is bypassed in mock mode by design ({@code routeToMock}), so this is a documented simulation,
         * not a faithful gate — the capture-hold money-safety stays untouched.
         */
        FRAUD_HOLD("fraud_hold", PaymentResponse.STATUS_REQUIRES_CAPTURE);

        private final String token;
        final String status;

        NonFailureOutcome(String token, String status) {
            this.token = token;
            this.status = status;
        }

        /** Case-insensitive, trimmed resolve; {@code null} when absent/unrecognized (back-compat — a typo
         * never alters the happy path). */
        static NonFailureOutcome lookup(Object raw) {
            if (raw == null) {
                return null;
            }
            String v = raw.toString().trim().toLowerCase(Locale.ROOT);
            if (v.isEmpty()) {
                return null;
            }
            for (NonFailureOutcome o : values()) {
                if (o.token.equals(v)) {
                    return o;
                }
            }
            return null;
        }
    }

    /**
     * TEST-6: the set of recognized {@code __test_outcome} values that force a NON-FAILURE outcome — exposed
     * for the catalog doc + tests (single source of truth, derived from {@link NonFailureOutcome}).
     */
    public static final Set<String> FORCED_NONFAILURE_OUTCOMES = Set.of(
            NonFailureOutcome.REQUIRES_ACTION.token,
            NonFailureOutcome.PROCESSING.token,
            NonFailureOutcome.FRAUD_HOLD.token);

    /** TEST-6 (A3): the {@code next_action.type} for the 3DS/SCA redirect stub. */
    public static final String NEXT_ACTION_REDIRECT = "redirect_to_url";

    /** TEST-6 (A3): the test 3DS redirect URL prefix; the minted pay id is appended (no hardcoded id, L-071). */
    public static final String TEST_3DS_URL_PREFIX = "https://test.nexuspay.local/3ds/";

    /**
     * Test-mode id prefixes (Stripe-style). PUBLIC so the {@code GatedPaymentGateway} can use them as a
     * defense-in-depth fail-safe: a {@code pay_test_}/{@code re_test_} id is, by construction, a mock
     * artifact that the real PSP never minted, so a refund/read targeting one MUST route to the mock even
     * on a deferred (approver/reconciler) thread whose {@code PaymentMode} no longer reflects the
     * originating test key (INT-3 refund-approval bypass fix).
     */
    public static final String PAY_PREFIX = "pay_test_";
    public static final String REFUND_PREFIX = "re_test_";
    private static final String TXN_PREFIX = "txn_test_";

    /** In-memory fake payment/refund stores — deterministic, no DB, no network, ever. */
    private final Map<String, PaymentResponse> payments = new ConcurrentHashMap<>();
    private final Map<String, RefundResponse> refunds = new ConcurrentHashMap<>();

    public MockPaymentGatewayPort() {
        // No collaborators — see class javadoc (synthesis is driven by the gateway).
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean isManual(String captureMethod) {
        return "manual".equalsIgnoreCase(captureMethod);
    }

    @Override
    public PaymentResponse createPayment(PaymentRequest request) {
        String id = PAY_PREFIX + uuid();
        String captureMethod = isManual(request.captureMethod()) ? "manual" : "automatic";
        Map<String, Object> metadata = request.metadata() != null ? request.metadata() : Map.of();

        // TEST-1 (TEST-MODE ONLY — this code is only reachable through the mock): honor a reserved
        // __test_outcome control key to force a deterministic FAILURE so an integrator can exercise
        // decline handling without a real declined card. Absent/"succeed"/UNKNOWN -> normal success below
        // (an unknown value must NEVER fail a happy-path test — a typo can't silently break it).
        ForcedOutcome forced = ForcedOutcome.lookup(metadata.get(TEST_OUTCOME_KEY));
        if (forced != null) {
            PaymentResponse failed = new PaymentResponse(
                    id, PaymentResponse.STATUS_FAILED, request.amount(), request.currency(), captureMethod,
                    request.customerId(), CONNECTOR, TXN_PREFIX + uuid(),
                    forced.errorCode, forced.errorMessage, Instant.now(), metadata);
            payments.put(id, failed); // stored so getPayment still works on a failed intent
            log.debug("Mock createPayment FORCED FAILURE: id={} outcome={} errorCode={}",
                    id, forced.token, forced.errorCode);
            return failed;
        }
        // TEST-6 (TEST-MODE ONLY): honor the same reserved control key for a forced NON-FAILURE outcome so an
        // integrator can exercise SCA/redirect (requires_action), async-settle (processing), and review-hold
        // ->capture (fraud_hold) flows without a real card. Resolved BEFORE the success default; absent/
        // unrecognized falls through to the byte-identical success path below (a typo never alters it). None
        // synthesizes a terminal webhook (all are non-terminal or a manual-capture shape).
        NonFailureOutcome nonFailure = NonFailureOutcome.lookup(metadata.get(TEST_OUTCOME_KEY));
        if (nonFailure != null) {
            PaymentResponse base = new PaymentResponse(
                    id, nonFailure.status, request.amount(), request.currency(), captureMethod,
                    request.customerId(), CONNECTOR, TXN_PREFIX + uuid(),
                    null, null, Instant.now(), metadata);
            // A3: attach a 3DS redirect stub derived from the just-minted id (no hardcoded server id, L-071).
            PaymentResponse response = nonFailure == NonFailureOutcome.REQUIRES_ACTION
                    ? base.withNextAction(new PaymentResponse.NextAction(NEXT_ACTION_REDIRECT, TEST_3DS_URL_PREFIX + id))
                    : base;
            payments.put(id, response); // stored so getPayment round-trips
            log.debug("Mock createPayment FORCED NON-FAILURE: id={} outcome={} status={}",
                    id, nonFailure.token, nonFailure.status);
            return response;
        }
        if (metadata.get(TEST_OUTCOME_KEY) != null) {
            // Present but unrecognized (or "succeed"): fall through to success, but make the no-op visible.
            log.debug("Mock createPayment: unrecognized {}={} — defaulting to the normal success path",
                    TEST_OUTCOME_KEY, metadata.get(TEST_OUTCOME_KEY));
        }

        // Auto-capture intents settle immediately (succeeded, a terminal MONEY state); manual intents
        // authorize only and wait for an explicit capture (requires_capture).
        String status = isManual(request.captureMethod())
                ? PaymentResponse.STATUS_REQUIRES_CAPTURE
                : PaymentResponse.STATUS_SUCCEEDED;
        // Echo the caller's amount/currency/customer/metadata back so the fake behaves like a real PSP
        // response for the round-trip; the capture method is normalized to automatic/manual.
        PaymentResponse response = new PaymentResponse(
                id, status, request.amount(), request.currency(), captureMethod,
                request.customerId(), CONNECTOR, TXN_PREFIX + uuid(),
                null, null, Instant.now(), metadata);
        payments.put(id, response);
        log.debug("Mock createPayment: id={} status={}", id, status);
        return response;
    }

    @Override
    public PaymentResponse confirmPayment(String paymentId, ConfirmRequest request) {
        PaymentResponse existing = require(paymentId);
        // Auto-capture intent settles on confirm; a manual intent stays requires_capture (capture later).
        String status = isManual(existing.captureMethod())
                ? PaymentResponse.STATUS_REQUIRES_CAPTURE
                : PaymentResponse.STATUS_SUCCEEDED;
        PaymentResponse updated = withStatus(existing, status, existing.amount());
        payments.put(paymentId, updated);
        return updated;
    }

    @Override
    public PaymentResponse capturePayment(String paymentId, CaptureRequest request) {
        PaymentResponse existing = require(paymentId);
        // Honor a partial capture amount (echo it back); full capture keeps the authorized amount.
        long captured = request != null && request.amountToCapture() != null
                ? request.amountToCapture()
                : existing.amount();
        PaymentResponse updated = withStatus(existing, PaymentResponse.STATUS_SUCCEEDED, captured);
        payments.put(paymentId, updated);
        return updated;
    }

    @Override
    public PaymentResponse voidPayment(String paymentId, VoidRequest request) {
        PaymentResponse existing = require(paymentId);
        PaymentResponse updated = withStatus(existing, PaymentResponse.STATUS_CANCELLED, existing.amount());
        payments.put(paymentId, updated);
        return updated;
    }

    @Override
    public PaymentResponse getPayment(String paymentId) {
        return require(paymentId); // NEVER network — read from the in-memory store or 404.
    }

    @Override
    public RefundResponse createRefund(RefundRequest request) {
        String id = REFUND_PREFIX + uuid();
        // TEST-1 (TEST-MODE ONLY): RefundRequest carries no metadata map, so a forced refund FAILURE is
        // signalled by a documented MAGIC AMOUNT — a refund whose minor-units amount % 100 == the reserved
        // sentinel fails with error_code "refund_failed". Any other amount keeps the SUCCEEDED behavior.
        boolean forceFailure = Math.floorMod(request.amount(), 100) == REFUND_FAILURE_SENTINEL;
        String status = forceFailure ? RefundResponse.STATUS_FAILED : RefundResponse.STATUS_SUCCEEDED;
        String errorCode = forceFailure ? REFUND_FAILED_CODE : null;
        String errorMessage = forceFailure ? "The refund failed at the processor." : null;
        RefundResponse response = new RefundResponse(
                id, request.paymentId(), status,
                request.amount(), request.currency(), request.reason(),
                CONNECTOR, TXN_PREFIX + uuid(), errorCode, errorMessage, Instant.now());
        refunds.put(id, response);
        log.debug("Mock createRefund: id={} paymentId={} status={}", id, request.paymentId(), status);
        return response;
    }

    @Override
    public RefundResponse getRefund(String refundId) {
        RefundResponse r = refunds.get(refundId);
        if (r == null) {
            throw PaymentException.notFound(refundId);
        }
        return r;
    }

    /** Loads a stored fake or mirrors the adapter's not-found shape. NEVER performs network I/O. */
    private PaymentResponse require(String paymentId) {
        PaymentResponse r = payments.get(paymentId);
        if (r == null) {
            throw PaymentException.notFound(paymentId);
        }
        return r;
    }

    private static PaymentResponse withStatus(PaymentResponse base, String status, long amount) {
        return new PaymentResponse(
                base.gatewayPaymentId(), status, amount, base.currency(), base.captureMethod(),
                base.customerId(), base.connectorName(), base.connectorTransactionId(),
                base.errorCode(), base.errorMessage(), base.createdAt(), base.metadata());
    }
}
