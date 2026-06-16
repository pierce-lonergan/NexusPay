package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.domain.ApiError;
import io.nexuspay.common.domain.ApiErrorResponse;
import io.nexuspay.gateway.adapter.in.rest.dto.ConfirmSessionRequest;
import io.nexuspay.gateway.application.port.in.TokenizePaymentMethodUseCase;
import io.nexuspay.gateway.application.port.out.PaymentTokenRepository;
import io.nexuspay.gateway.application.service.PaymentSessionService;
import io.nexuspay.gateway.domain.PaymentSession;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.CallContext;
import io.nexuspay.payment.application.screening.ScreeningMode;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * INT-2 Invariant 4 (B-029): the SDK confirm chokepoint creates the real gateway payment through the
 * @Primary GatedPaymentGateway, forwarding the SERVER-STORED session metadata and a trusted
 * {@code CallContext.interactive(tenantId)} sourced from the session principal — never the client body.
 *
 * <p>This is the slice/unit half of the parity coverage; the IT
 * ({@code CheckoutSessionWebhookMetadataIT}) verifies the INT-1 store round-trip on real Postgres.</p>
 *
 * <p>FAILS if the confirm TODO is reverted (createPayment un-called → no captor interaction →
 * verification fails), or if the metadata/tenant source is changed to the client body.</p>
 */
class CheckoutConfirmMetadataParityTest {

    private PaymentSessionService sessionService;
    private PaymentTokenRepository paymentTokenRepository;
    private PaymentGatewayPort paymentGateway;
    private CheckoutController controller;

    private final NexusPayPrincipal principal = new NexusPayPrincipal(
            "checkout_user", "tenant-A", "operator", NexusPayPrincipal.AuthMethod.SESSION_TOKEN, "ps_1");

    @BeforeEach
    void setUp() {
        sessionService = mock(PaymentSessionService.class);
        var tokenizeUseCase = mock(TokenizePaymentMethodUseCase.class);
        paymentTokenRepository = mock(PaymentTokenRepository.class);
        paymentGateway = mock(PaymentGatewayPort.class);
        controller = new CheckoutController(sessionService, tokenizeUseCase, paymentTokenRepository, paymentGateway);
    }

    private PaymentSession openSession(Map<String, Object> metadata) {
        Instant now = Instant.now();
        return new PaymentSession("ps_1", "tenant-A", null, "secret",
                5000L, "USD", PaymentSession.STATUS_OPEN, "cust_1", List.of("card"),
                "https://example.com/success", "https://example.com/cancel",
                Map.of(), metadata, 0, now.plusSeconds(600), now, now);
    }

    @Test
    void confirm_forwardsServerStoredMetadata_andTrustedInteractiveContext() {
        Map<String, Object> sessionMeta = new LinkedHashMap<>();
        sessionMeta.put("userId", "u_42");
        sessionMeta.put("packId", "gold");
        var session = openSession(sessionMeta);

        when(sessionService.findById("ps_1")).thenReturn(Optional.of(session));
        when(paymentTokenRepository.findById(any())).thenReturn(Optional.empty());
        when(paymentGateway.createPayment(any(PaymentRequest.class), any(CallContext.class)))
                .thenReturn(new PaymentResponse("pay_int2", "succeeded", 5000L, "USD",
                        "automatic", "cust_1", "stripe", "ctxn", null, null, Instant.now(), Map.of()));

        controller.confirmPayment(new ConfirmSessionRequest("ptok_1"), principal);

        ArgumentCaptor<PaymentRequest> reqCap = ArgumentCaptor.forClass(PaymentRequest.class);
        ArgumentCaptor<CallContext> ctxCap = ArgumentCaptor.forClass(CallContext.class);
        verify(paymentGateway).createPayment(reqCap.capture(), ctxCap.capture());

        // Forwarded metadata is the server-stored session metadata (correlation keys preserved).
        assertThat(reqCap.getValue().metadata())
                .containsEntry("userId", "u_42")
                .containsEntry("packId", "gold");
        // Trusted CallContext: tenant from the principal (NOT the body), INTERACTIVE rail.
        assertThat(ctxCap.getValue().tenantId()).isEqualTo("tenant-A");
        assertThat(ctxCap.getValue().mode()).isEqualTo(ScreeningMode.INTERACTIVE);

        // Session completed with the gateway payment id returned by the gate.
        verify(sessionService).completeSession(eq("ps_1"), eq("pay_int2"));
    }

    @Test
    void confirm_expiredSession_returnsGone_andNeverCreatesPayment() {
        Instant past = Instant.now().minusSeconds(600);
        var expired = new PaymentSession("ps_1", "tenant-A", null, "secret",
                5000L, "USD", PaymentSession.STATUS_OPEN, "cust_1", List.of("card"),
                "https://example.com/success", "https://example.com/cancel",
                Map.of(), Map.of(), 0, past, past, past);
        when(sessionService.findById("ps_1")).thenReturn(Optional.of(expired));

        var resp = controller.confirmPayment(new ConfirmSessionRequest("ptok_1"), principal);

        assertThat(resp.getStatusCode().value()).isEqualTo(410);
        // INT-2: the 410 body is the stable envelope with the documented TYPE_SESSION taxonomy + request_id.
        assertThat(resp.getBody()).isInstanceOf(ApiErrorResponse.class);
        ApiError err = ((ApiErrorResponse) resp.getBody()).error();
        assertThat(err.type()).isEqualTo(ApiError.TYPE_SESSION);
        assertThat(err.code()).isEqualTo("session_expired");
        assertThat(err.requestId()).isNotBlank();
        org.mockito.Mockito.verify(paymentGateway, org.mockito.Mockito.never())
                .createPayment(any(PaymentRequest.class), any(CallContext.class));
    }
}
