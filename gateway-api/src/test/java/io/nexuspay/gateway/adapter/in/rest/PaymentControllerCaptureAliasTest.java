package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.CreatePaymentRequest;
import io.nexuspay.gateway.application.RefundOrchestrationService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.CallContext;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * INT-2 Invariant 1: the {@code capture} boolean is a convenience alias for {@code capture_method}.
 * {@code capture_method} is authoritative when both are present; the alias only fills a null/blank
 * {@code capture_method} (true→automatic, false→manual). Asserts the {@code captureMethod} actually
 * forwarded to the gateway via an ArgumentCaptor.
 *
 * <p>FAILS if the alias mapping (cases 1/2) or the "capture_method authoritative" guard (case 3) is
 * reverted.</p>
 */
class PaymentControllerCaptureAliasTest {

    private PaymentGatewayPort gateway;
    private PaymentController controller;
    private final NexusPayPrincipal principal =
            new NexusPayPrincipal("op_1", "t1", "operator", NexusPayPrincipal.AuthMethod.JWT);

    @BeforeEach
    void setUp() {
        gateway = mock(PaymentGatewayPort.class);
        var refundOrchestration = mock(RefundOrchestrationService.class);
        var screeningOrigins = mock(ScreeningOriginService.class);
        controller = new PaymentController(gateway, refundOrchestration, screeningOrigins);

        when(gateway.createPayment(any(PaymentRequest.class), any(CallContext.class)))
                .thenReturn(new PaymentResponse("pay_1", "requires_payment_method", 5000L, "USD",
                        "automatic", "cust_1", "stripe", "ctxn", null, null, Instant.now(), Map.of()));
    }

    private String capturedCaptureMethod(CreatePaymentRequest req) {
        controller.createPayment(req, null, null, principal);
        ArgumentCaptor<PaymentRequest> cap = ArgumentCaptor.forClass(PaymentRequest.class);
        org.mockito.Mockito.verify(gateway).createPayment(cap.capture(), any(CallContext.class));
        return cap.getValue().captureMethod();
    }

    private CreatePaymentRequest request(String captureMethod, Boolean capture) {
        return new CreatePaymentRequest(5000L, "USD", "cust_1", "card", null, null, null,
                captureMethod, capture, null,
                // TEST-3c off-session fields (absent on the inline-card path): payment_method/off_session/
                // setup_future_usage/mandate_id.
                null, null, null, null);
    }

    @Test
    void captureTrue_noCaptureMethod_mapsToAutomatic() {
        assertThat(capturedCaptureMethod(request(null, Boolean.TRUE))).isEqualTo("automatic");
    }

    @Test
    void captureFalse_blankCaptureMethod_mapsToManual() {
        assertThat(capturedCaptureMethod(request("  ", Boolean.FALSE))).isEqualTo("manual");
    }

    @Test
    void captureMethodIsAuthoritative_whenBothSupplied() {
        // capture=true would say "automatic", but capture_method="manual" must win.
        assertThat(capturedCaptureMethod(request("manual", Boolean.TRUE))).isEqualTo("manual");
    }

    @Test
    void noAlias_explicitCaptureMethodPassesThrough() {
        assertThat(capturedCaptureMethod(request("automatic", null))).isEqualTo("automatic");
    }

    @Test
    void noAlias_noCaptureMethod_remainsNull() {
        assertThat(capturedCaptureMethod(request(null, null))).isNull();
    }
}
