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
import java.util.Map;
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
 */
@Component
public class MockPaymentGatewayPort implements PaymentGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGatewayPort.class);

    /** Connector name stamped on every mock response (mirrors HyperSwitch's connector field). */
    public static final String CONNECTOR = "mock";

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
        // Auto-capture intents settle immediately (succeeded, a terminal MONEY state); manual intents
        // authorize only and wait for an explicit capture (requires_capture).
        String status = isManual(request.captureMethod())
                ? PaymentResponse.STATUS_REQUIRES_CAPTURE
                : PaymentResponse.STATUS_SUCCEEDED;
        // Echo the caller's amount/currency/customer/metadata back so the fake behaves like a real PSP
        // response for the round-trip; the capture method is normalized to automatic/manual.
        String captureMethod = isManual(request.captureMethod()) ? "manual" : "automatic";
        PaymentResponse response = new PaymentResponse(
                id, status, request.amount(), request.currency(), captureMethod,
                request.customerId(), CONNECTOR, TXN_PREFIX + uuid(),
                null, null, Instant.now(),
                request.metadata() != null ? request.metadata() : Map.of());
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
        RefundResponse response = new RefundResponse(
                id, request.paymentId(), RefundResponse.STATUS_SUCCEEDED,
                request.amount(), request.currency(), request.reason(),
                CONNECTOR, TXN_PREFIX + uuid(), null, null, Instant.now());
        refunds.put(id, response);
        log.debug("Mock createRefund: id={} paymentId={}", id, request.paymentId());
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
