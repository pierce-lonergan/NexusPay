package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.gateway.adapter.in.rest.dto.ConfirmResponse;
import io.nexuspay.gateway.adapter.in.rest.dto.ConfirmSessionRequest;
import io.nexuspay.gateway.adapter.in.rest.dto.SessionStatusResponse;
import io.nexuspay.gateway.application.port.in.TokenizePaymentMethodUseCase;
import io.nexuspay.gateway.application.port.out.PaymentTokenRepository;
import io.nexuspay.gateway.application.service.PaymentSessionService;
import io.nexuspay.gateway.domain.PaymentSession;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.CallContext;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * INT-6: {@code POST /v1/checkout/confirm} must return a proper, status-accurate {@link ConfirmResponse}
 * (the shape {@code @nexus-pay/js}'s {@code confirm()} consumes) — NOT the re-fetched {@code session}
 * {@link SessionStatusResponse} the pre-INT-6 code returned.
 *
 * <p>Each test pins a distinct invariant; reverting any of the INT-6 changes fails a distinct assertion:
 * <ul>
 *   <li>reverting to {@code toSessionStatusResponse} fails the {@code instanceof ConfirmResponse} + status
 *       assertions (the session status is always {@code "complete"}, never a confirm outcome);</li>
 *   <li>mapping a held payment to {@code succeeded} fails {@link #confirm_heldPayment_returnsRequiresAction_notSucceeded};</li>
 *   <li>catching the gate {@link PaymentException} fails {@link #confirm_gateBlock_propagatesPaymentException};</li>
 *   <li>removing the idempotent short-circuit re-runs {@code createPayment} and fails the {@code never()}
 *       verifications in {@link #confirm_completedSession_isIdempotent_noSecondCharge}.</li>
 * </ul>
 */
class CheckoutConfirmResultTest {

    private PaymentSessionService sessionService;
    private PaymentTokenRepository paymentTokenRepository;
    private PaymentGatewayPort paymentGateway;
    private CheckoutController controller;

    /** Session-scoped principal authenticated via a TEST key (live=false) -> mode "test", livemode false. */
    private final NexusPayPrincipal testPrincipal = new NexusPayPrincipal(
            "checkout_user", "tenant-A", "operator",
            NexusPayPrincipal.AuthMethod.SESSION_TOKEN, "ps_1", false);

    @BeforeEach
    void setUp() {
        sessionService = mock(PaymentSessionService.class);
        var tokenizeUseCase = mock(TokenizePaymentMethodUseCase.class);
        paymentTokenRepository = mock(PaymentTokenRepository.class);
        paymentGateway = mock(PaymentGatewayPort.class);
        controller = new CheckoutController(sessionService, tokenizeUseCase, paymentTokenRepository, paymentGateway);
    }

    private PaymentSession openSession() {
        Instant now = Instant.now();
        return new PaymentSession("ps_1", "tenant-A", null, "secret",
                5000L, "USD", PaymentSession.STATUS_OPEN, "cust_1", List.of("card"),
                "https://example.com/success", "https://example.com/cancel",
                Map.of(), Map.of(), 0, now.plusSeconds(600), now, now);
    }

    private PaymentSession completeSession(String paymentIntentId) {
        Instant now = Instant.now();
        return new PaymentSession("ps_1", "tenant-A", paymentIntentId, "secret",
                5000L, "USD", PaymentSession.STATUS_COMPLETE, "cust_1", List.of("card"),
                "https://example.com/success", "https://example.com/cancel",
                Map.of(), Map.of(), 0, now.plusSeconds(600), now, now);
    }

    private static PaymentResponse payment(String id, String status, String errorCode,
                                           String errorMessage, Map<String, Object> metadata) {
        return new PaymentResponse(id, status, 5000L, "USD", "automatic", "cust_1",
                "mock", "txn_1", errorCode, errorMessage, Instant.now(), metadata);
    }

    private static ConfirmResponse confirmBody(org.springframework.http.ResponseEntity<?> resp) {
        assertThat(resp.getBody())
                .as("INT-6: confirm must return ConfirmResponse, NOT the session SessionStatusResponse")
                .isInstanceOf(ConfirmResponse.class);
        return (ConfirmResponse) resp.getBody();
    }

    // --- A1: captured payment -> succeeded ConfirmResponse (the core return-type fix) ---

    @Test
    void confirm_capturedPayment_returnsSucceededConfirmResponse() {
        when(sessionService.findById("ps_1")).thenReturn(Optional.of(openSession()));
        when(paymentTokenRepository.findById(any())).thenReturn(Optional.empty());
        when(paymentGateway.createPayment(any(PaymentRequest.class), any(CallContext.class)))
                .thenReturn(payment("pay_test_x", PaymentResponse.STATUS_SUCCEEDED, null, null, Map.of()));

        var resp = controller.confirmPayment(new ConfirmSessionRequest("ptok_1"), testPrincipal);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        ConfirmResponse body = confirmBody(resp);
        assertThat(body.status()).isEqualTo("succeeded");
        assertThat(body.paymentId()).isEqualTo("pay_test_x");
        assertThat(body.mode()).isEqualTo("test");
        assertThat(body.livemode()).isFalse();
        assertThat(body.nextAction()).isNull();
        assertThat(body.error()).isNull();
        // Guard: the body is NOT the session status object.
        assertThat(resp.getBody()).isNotInstanceOf(SessionStatusResponse.class);
    }

    // --- A2: held / processing -> requires_action / processing, NEVER succeeded (Invariant 2) ---

    @Test
    void confirm_heldPayment_returnsRequiresAction_notSucceeded() {
        when(sessionService.findById("ps_1")).thenReturn(Optional.of(openSession()));
        when(paymentTokenRepository.findById(any())).thenReturn(Optional.empty());
        when(paymentGateway.createPayment(any(PaymentRequest.class), any(CallContext.class)))
                .thenReturn(payment("pay_live_held", PaymentResponse.STATUS_REQUIRES_CAPTURE, null, null, Map.of()));

        var resp = controller.confirmPayment(new ConfirmSessionRequest("ptok_1"), testPrincipal);

        ConfirmResponse body = confirmBody(resp);
        assertThat(body.status()).as("a HELD payment must NOT be reported succeeded").isNotEqualTo("succeeded");
        assertThat(body.status()).isEqualTo("requires_action");
        // The held case carries no redirect (the client shows "under review" from the status alone).
        assertThat(body.nextAction()).isNull();
    }

    @Test
    void confirm_processingPayment_returnsProcessing() {
        when(sessionService.findById("ps_1")).thenReturn(Optional.of(openSession()));
        when(paymentTokenRepository.findById(any())).thenReturn(Optional.empty());
        when(paymentGateway.createPayment(any(PaymentRequest.class), any(CallContext.class)))
                .thenReturn(payment("pay_proc", PaymentResponse.STATUS_PROCESSING, null, null, Map.of()));

        ConfirmResponse body = confirmBody(controller.confirmPayment(new ConfirmSessionRequest("ptok_1"), testPrincipal));
        assertThat(body.status()).isEqualTo("processing");
    }

    @Test
    void confirm_unknownStatus_failsSafeToProcessing_neverSucceeded() {
        when(sessionService.findById("ps_1")).thenReturn(Optional.of(openSession()));
        when(paymentTokenRepository.findById(any())).thenReturn(Optional.empty());
        when(paymentGateway.createPayment(any(PaymentRequest.class), any(CallContext.class)))
                .thenReturn(payment("pay_weird", "some_future_state", null, null, Map.of()));

        ConfirmResponse body = confirmBody(controller.confirmPayment(new ConfirmSessionRequest("ptok_1"), testPrincipal));
        assertThat(body.status()).isEqualTo("processing");
        assertThat(body.status()).isNotEqualTo("succeeded");
    }

    // --- A3: failed -> failed with mapped error ---

    @Test
    void confirm_failedPayment_returnsFailedWithError() {
        when(sessionService.findById("ps_1")).thenReturn(Optional.of(openSession()));
        when(paymentTokenRepository.findById(any())).thenReturn(Optional.empty());
        when(paymentGateway.createPayment(any(PaymentRequest.class), any(CallContext.class)))
                .thenReturn(payment("pay_fail", PaymentResponse.STATUS_FAILED,
                        "card_declined", "Your card was declined", Map.of()));

        ConfirmResponse body = confirmBody(controller.confirmPayment(new ConfirmSessionRequest("ptok_1"), testPrincipal));
        assertThat(body.status()).isEqualTo("failed");
        assertThat(body.error()).isNotNull();
        assertThat(body.error().type()).isEqualTo("payment_error");
        assertThat(body.error().code()).isEqualTo("card_declined");
        assertThat(body.error().message()).isEqualTo("Your card was declined");
        assertThat(body.nextAction()).isNull();
    }

    @Test
    void confirm_cancelledPayment_returnsFailed() {
        when(sessionService.findById("ps_1")).thenReturn(Optional.of(openSession()));
        when(paymentTokenRepository.findById(any())).thenReturn(Optional.empty());
        when(paymentGateway.createPayment(any(PaymentRequest.class), any(CallContext.class)))
                .thenReturn(payment("pay_void", PaymentResponse.STATUS_CANCELLED, null, null, Map.of()));

        ConfirmResponse body = confirmBody(controller.confirmPayment(new ConfirmSessionRequest("ptok_1"), testPrincipal));
        assertThat(body.status()).isEqualTo("failed");
    }

    // --- A4: 3DS intent -> requires_action with nextAction derived from intent metadata ---

    @Test
    void confirm_3dsIntent_returnsRequiresActionWithNextAction() {
        Map<String, Object> meta = Map.of("next_action",
                Map.of("type", "three_d_secure", "url", "https://3ds.example.com/c"));
        when(sessionService.findById("ps_1")).thenReturn(Optional.of(openSession()));
        when(paymentTokenRepository.findById(any())).thenReturn(Optional.empty());
        // PaymentResponse has no STATUS_REQUIRES_ACTION constant — HyperSwitch surfaces the raw string.
        when(paymentGateway.createPayment(any(PaymentRequest.class), any(CallContext.class)))
                .thenReturn(payment("pay_3ds", "requires_action", null, null, meta));

        ConfirmResponse body = confirmBody(controller.confirmPayment(new ConfirmSessionRequest("ptok_1"), testPrincipal));
        assertThat(body.status()).isEqualTo("requires_action");
        assertThat(body.nextAction()).isNotNull();
        assertThat(body.nextAction().type()).isEqualTo("three_d_secure");
        assertThat(body.nextAction().url()).isEqualTo("https://3ds.example.com/c");
    }

    // --- A5: gate block -> PaymentException propagates (NOT caught, NOT a 500) ---

    @Test
    void confirm_gateBlock_propagatesPaymentException() {
        when(sessionService.findById("ps_1")).thenReturn(Optional.of(openSession()));
        when(paymentTokenRepository.findById(any())).thenReturn(Optional.empty());
        when(paymentGateway.createPayment(any(PaymentRequest.class), any(CallContext.class)))
                .thenThrow(new PaymentException("Payment blocked", "fraud_blocked"));

        // The controller does NOT catch PaymentException — it propagates to GlobalExceptionHandler
        // (which renders the INT-2 envelope with the right status). Catching it here would mask the gate.
        assertThatThrownBy(() -> controller.confirmPayment(new ConfirmSessionRequest("ptok_1"), testPrincipal))
                .isInstanceOf(PaymentException.class)
                .hasFieldOrPropertyWithValue("errorCode", "fraud_blocked");

        // A gate block must NOT complete the session (no charge happened).
        verify(sessionService, never()).completeSession(any(), any());
    }

    // --- A6: completed session -> idempotent re-confirm, read-only, no second charge ---

    @Test
    void confirm_completedSession_isIdempotent_noSecondCharge() {
        when(sessionService.findById("ps_1")).thenReturn(Optional.of(completeSession("pay_test_x")));
        when(paymentGateway.getPayment("pay_test_x"))
                .thenReturn(payment("pay_test_x", PaymentResponse.STATUS_SUCCEEDED, null, null, Map.of()));

        var resp = controller.confirmPayment(new ConfirmSessionRequest("ptok_1"), testPrincipal);

        ConfirmResponse body = confirmBody(resp);
        assertThat(body.status()).isEqualTo("succeeded");
        assertThat(body.paymentId()).isEqualTo("pay_test_x");
        assertThat(body.mode()).isEqualTo("test");

        // Read-only re-confirm: NO second charge, NO second completeSession.
        verify(paymentGateway).getPayment("pay_test_x");
        verify(paymentGateway, never()).createPayment(any(PaymentRequest.class), any(CallContext.class));
        verify(sessionService, never()).completeSession(any(), any());
    }

    // --- A7: expired session -> 410, gateway never touched (kept distinct from re-confirm) ---

    @Test
    void confirm_expiredSession_returnsGone_neverTouchesGateway() {
        Instant past = Instant.now().minusSeconds(600);
        var expired = new PaymentSession("ps_1", "tenant-A", null, "secret",
                5000L, "USD", PaymentSession.STATUS_OPEN, "cust_1", List.of("card"),
                "https://example.com/success", "https://example.com/cancel",
                Map.of(), Map.of(), 0, past, past, past);
        when(sessionService.findById("ps_1")).thenReturn(Optional.of(expired));

        var resp = controller.confirmPayment(new ConfirmSessionRequest("ptok_1"), testPrincipal);

        assertThat(resp.getStatusCode().value()).isEqualTo(410);
        verify(paymentGateway, never()).createPayment(any(PaymentRequest.class), any(CallContext.class));
        verify(paymentGateway, never()).getPayment(eq("pay_test_x"));
    }
}
