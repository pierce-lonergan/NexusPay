package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.CancelPaymentRequest;
import io.nexuspay.gateway.adapter.in.rest.dto.CapturePaymentRequest;
import io.nexuspay.gateway.adapter.in.rest.dto.CreateRefundRequest;
import io.nexuspay.gateway.application.RefundOrchestrationService;
import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.domain.CaptureRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.RefundResponse;
import io.nexuspay.payment.domain.VoidRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SEC-12: the capture / void / refund endpoints must thread a NON-NULL idempotency key to the PSP even
 * when the caller omits the {@code Idempotency-Key} header. The controller resolves the key:
 * <ul>
 *   <li>the caller key is AUTHORITATIVE — forwarded unchanged when present (non-null, non-blank);</li>
 *   <li>otherwise a DETERMINISTIC per-op key is derived — {@code capture-{id}-{amt|"full"}},
 *       {@code void-{id}}, {@code refund-{id}-{amount}} — so a network RETRY of the SAME logical op
 *       re-derives the IDENTICAL key and HyperSwitch dedups it (no double capture / void / refund).</li>
 * </ul>
 *
 * <p>Built by direct construction (the gateway-api controller-test convention, see
 * {@code PaymentControllerOwnershipTest}). {@link ScreeningOriginService#assertOwnedBy} is a no-op (a
 * void mock method) so the SEC-07 ownership check passes; the refund path uses a REAL
 * {@link RefundOrchestrationService} wired to a mocked {@link PaymentGatewayPort} so the captured
 * {@link RefundRequest} is the one that actually reaches the PSP through the sub-threshold path.</p>
 *
 * <p><strong>FAILS if the SEC-12 key derivation is reverted</strong> — vulnerable code forwards the raw
 * null/blank header, so the captured {@code idempotencyKey()} would be null instead of the derived key,
 * and the caller-key-honored / distinctness assertions would not hold.</p>
 */
@DisplayName("SEC-12 server-derived idempotency key for capture/void/refund")
class PaymentIdempotencyKeyDerivationTest {

    private static final String TENANT = "tenant-A";
    private static final String PAY = "pay_abc";
    private static final long SUB_THRESHOLD = 2500L; // < 50000 approval threshold -> direct-to-PSP path

    private final NexusPayPrincipal operator =
            new NexusPayPrincipal("op_1", TENANT, "operator", NexusPayPrincipal.AuthMethod.JWT);

    private PaymentGatewayPort gateway;
    private PaymentGatewayPort refundPsp;
    private RefundOrchestrationService refundOrchestration;
    private ScreeningOriginService screeningOrigins;
    private PaymentController controller;

    @BeforeEach
    void setUp() {
        gateway = mock(PaymentGatewayPort.class);
        refundPsp = mock(PaymentGatewayPort.class);
        screeningOrigins = mock(ScreeningOriginService.class);

        when(gateway.capturePayment(any(), any(CaptureRequest.class))).thenReturn(okResponse());
        when(gateway.voidPayment(any(), any(VoidRequest.class))).thenReturn(okResponse());
        when(refundPsp.createRefund(any(RefundRequest.class)))
                .thenAnswer(inv -> refundResponse(inv.getArgument(0)));

        // REAL refund orchestrator over a mocked PSP: the sub-threshold (amount < 50000) branch forwards
        // the controller-resolved key straight into the RefundRequest reaching refundPsp. Ownership is a
        // no-op (mocked ScreeningOriginService) so createRefund proceeds to the PSP path.
        refundOrchestration = new RefundOrchestrationService(
                refundPsp, mock(ApprovalService.class), screeningOrigins, 50000L);

        controller = new PaymentController(gateway, refundOrchestration, screeningOrigins);
    }

    private static PaymentResponse okResponse() {
        return new PaymentResponse(PAY, PaymentResponse.STATUS_SUCCEEDED, 1000, "USD",
                "manual", "cus_1", "stripe", "con_1", null, null, Instant.now(), Map.of());
    }

    private static RefundResponse refundResponse(RefundRequest req) {
        return new RefundResponse("re_1", req.paymentId(), RefundResponse.STATUS_SUCCEEDED,
                req.amount(), req.currency(), req.reason(), "stripe", "conn_1", null, null, Instant.now());
    }

    private CaptureRequest captureCaptor() {
        ArgumentCaptor<CaptureRequest> c = ArgumentCaptor.forClass(CaptureRequest.class);
        org.mockito.Mockito.verify(gateway).capturePayment(eq(PAY), c.capture());
        return c.getValue();
    }

    private VoidRequest voidCaptor() {
        ArgumentCaptor<VoidRequest> c = ArgumentCaptor.forClass(VoidRequest.class);
        org.mockito.Mockito.verify(gateway).voidPayment(eq(PAY), c.capture());
        return c.getValue();
    }

    private RefundRequest refundCaptor() {
        ArgumentCaptor<RefundRequest> c = ArgumentCaptor.forClass(RefundRequest.class);
        org.mockito.Mockito.verify(refundPsp).createRefund(c.capture());
        return c.getValue();
    }

    // ---- 1. Absent header -> stable, deterministic server key (each op) ----

    @Test
    @DisplayName("capture: absent header -> deterministic 'capture-{id}-{amt}' key (FAILS if derivation reverted)")
    void capture_absentHeader_derivesDeterministicKey() {
        controller.capturePayment(PAY, new CapturePaymentRequest(750L), null, operator);
        assertThat(captureCaptor().idempotencyKey())
                .as("a null Idempotency-Key header must NOT be forwarded raw; derive a stable key")
                .isEqualTo("capture-" + PAY + "-750");
    }

    @Test
    @DisplayName("capture: full capture (null amount) -> 'capture-{id}-full'")
    void capture_fullCapture_usesFullAmountSegment() {
        controller.capturePayment(PAY, new CapturePaymentRequest(null), null, operator);
        assertThat(captureCaptor().idempotencyKey()).isEqualTo("capture-" + PAY + "-full");
    }

    @Test
    @DisplayName("void: absent header -> deterministic 'void-{id}' key (no amount segment)")
    void void_absentHeader_derivesDeterministicKey() {
        controller.cancelPayment(PAY, new CancelPaymentRequest("requested"), null, operator);
        assertThat(voidCaptor().idempotencyKey()).isEqualTo("void-" + PAY);
    }

    @Test
    @DisplayName("refund: absent header -> deterministic 'refund-{id}-{amount}' key")
    void refund_absentHeader_derivesDeterministicKey() {
        controller.createRefund(PAY, new CreateRefundRequest(SUB_THRESHOLD, "USD", "dup"), null, operator);
        assertThat(refundCaptor().idempotencyKey()).isEqualTo("refund-" + PAY + "-" + SUB_THRESHOLD);
    }

    @Test
    @DisplayName("retry-safety: a second identical absent-header call derives the IDENTICAL key")
    void retry_sameLogicalOp_yieldsIdenticalKey() {
        // First capture, then a "retried" capture of the same payment+amount with no caller key.
        var g1 = mock(PaymentGatewayPort.class);
        when(g1.capturePayment(any(), any(CaptureRequest.class))).thenReturn(okResponse());
        var c1 = new PaymentController(g1, refundOrchestration, screeningOrigins);

        c1.capturePayment(PAY, new CapturePaymentRequest(750L), null, operator);
        c1.capturePayment(PAY, new CapturePaymentRequest(750L), null, operator);

        ArgumentCaptor<CaptureRequest> cap = ArgumentCaptor.forClass(CaptureRequest.class);
        org.mockito.Mockito.verify(g1, org.mockito.Mockito.times(2))
                .capturePayment(eq(PAY), cap.capture());
        assertThat(cap.getAllValues())
                .as("a network retry of the SAME logical op must re-derive the IDENTICAL key -> PSP dedup")
                .extracting(CaptureRequest::idempotencyKey)
                .containsExactly("capture-" + PAY + "-750", "capture-" + PAY + "-750");
    }

    // ---- 2. Blank header -> treated as absent -> derived ----

    @Test
    @DisplayName("blank header is treated as absent -> derived key (capture)")
    void capture_blankHeader_derivesKey() {
        controller.capturePayment(PAY, new CapturePaymentRequest(750L), "   ", operator);
        assertThat(captureCaptor().idempotencyKey()).isEqualTo("capture-" + PAY + "-750");
    }

    // ---- 3. Caller key is authoritative -> forwarded unchanged ----

    @Test
    @DisplayName("caller key wins: present header forwarded unchanged (FAILS if code always derives)")
    void capture_callerKey_isAuthoritative() {
        controller.capturePayment(PAY, new CapturePaymentRequest(750L), "cust-key-1", operator);
        assertThat(captureCaptor().idempotencyKey())
                .as("a present caller key must be forwarded verbatim, never overridden by a derived key")
                .isEqualTo("cust-key-1");
    }

    @Test
    @DisplayName("caller key wins on void and refund too")
    void voidAndRefund_callerKey_isAuthoritative() {
        controller.cancelPayment(PAY, new CancelPaymentRequest("requested"), "cust-key-2", operator);
        assertThat(voidCaptor().idempotencyKey()).isEqualTo("cust-key-2");

        controller.createRefund(PAY, new CreateRefundRequest(SUB_THRESHOLD, "USD", "dup"), "cust-key-3", operator);
        assertThat(refundCaptor().idempotencyKey()).isEqualTo("cust-key-3");
    }

    // ---- 4. Cross-op / cross-amount distinctness (no collision) ----

    @Test
    @DisplayName("derived keys are distinct across op and across amount (no collision)")
    void derivedKeys_areDistinct_acrossOpAndAmount() {
        // capture (full) on a fresh controller per op so each captor sees exactly one call.
        var gCap = mock(PaymentGatewayPort.class);
        when(gCap.capturePayment(any(), any(CaptureRequest.class))).thenReturn(okResponse());
        new PaymentController(gCap, refundOrchestration, screeningOrigins)
                .capturePayment(PAY, new CapturePaymentRequest(null), null, operator);
        ArgumentCaptor<CaptureRequest> capC = ArgumentCaptor.forClass(CaptureRequest.class);
        org.mockito.Mockito.verify(gCap).capturePayment(eq(PAY), capC.capture());
        String captureKey = capC.getValue().idempotencyKey();

        // two refunds of distinct amounts via the real orchestrator over a fresh PSP mock.
        var rPsp = mock(PaymentGatewayPort.class);
        when(rPsp.createRefund(any(RefundRequest.class))).thenAnswer(inv -> refundResponse(inv.getArgument(0)));
        var refundSvc = new RefundOrchestrationService(rPsp, mock(ApprovalService.class), screeningOrigins, 50000L);
        var rController = new PaymentController(gateway, refundSvc, screeningOrigins);
        rController.createRefund(PAY, new CreateRefundRequest(2500L, "USD", "a"), null, operator);
        rController.createRefund(PAY, new CreateRefundRequest(5000L, "USD", "b"), null, operator);
        ArgumentCaptor<RefundRequest> refC = ArgumentCaptor.forClass(RefundRequest.class);
        org.mockito.Mockito.verify(rPsp, org.mockito.Mockito.times(2)).createRefund(refC.capture());

        assertThat(captureKey).isEqualTo("capture-" + PAY + "-full");
        assertThat(refC.getAllValues())
                .extracting(RefundRequest::idempotencyKey)
                .containsExactly("refund-" + PAY + "-2500", "refund-" + PAY + "-5000");
        // capture-full, refund-2500, refund-5000 are three distinct keys.
        assertThat(captureKey)
                .isNotEqualTo(refC.getAllValues().get(0).idempotencyKey())
                .isNotEqualTo(refC.getAllValues().get(1).idempotencyKey());
        assertThat(refC.getAllValues().get(0).idempotencyKey())
                .isNotEqualTo(refC.getAllValues().get(1).idempotencyKey());
    }
}
