package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.exception.InvalidRequestException;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.gateway.adapter.in.rest.dto.CreatePaymentRequest;
import io.nexuspay.gateway.application.RefundOrchestrationService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.CallContext;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.application.service.OffSessionChargeService;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TEST-3c: {@code PaymentController.createPayment} delegates to {@link OffSessionChargeService} when a
 * saved {@code payment_method} (pm_) is present, and runs the inline-card path byte-identically when it is
 * absent. Direct controller construction with mocked collaborators (mirrors
 * {@code PaymentControllerCaptureAliasTest}); no Spring context.
 *
 * <p>Asserts: the off-session delegation passes {@code principal.tenantId()} + {@code isTest} derived from
 * {@code principal.live()}; a 404 ({@link ResourceNotFoundException}) and a 400
 * ({@link InvalidRequestException}) from the service propagate (the gateway is never reached); and the
 * BACK-COMPAT inline path does NOT invoke the off-session service and calls
 * {@code paymentGateway.createPayment} with all four off-session fields null.</p>
 */
class PaymentControllerOffSessionTest {

    private PaymentGatewayPort gateway;
    private OffSessionChargeService offSession;
    private PaymentController controller;

    /** A LIVE operator principal (live()==true -> isTest==false). */
    private final NexusPayPrincipal livePrincipal =
            new NexusPayPrincipal("op_1", "t1", "operator", NexusPayPrincipal.AuthMethod.JWT);
    /** A TEST (sk_test_) operator principal (live()==false -> isTest==true). */
    private final NexusPayPrincipal testPrincipal =
            new NexusPayPrincipal("op_1", "t1", "operator", NexusPayPrincipal.AuthMethod.API_KEY, null, false);

    @BeforeEach
    void setUp() {
        gateway = mock(PaymentGatewayPort.class);
        offSession = mock(OffSessionChargeService.class);
        var refundOrchestration = mock(RefundOrchestrationService.class);
        var screeningOrigins = mock(ScreeningOriginService.class);
        controller = new PaymentController(gateway, refundOrchestration, screeningOrigins, offSession,
                mock(io.nexuspay.payment.application.service.projection.PaymentProjectionQueryService.class));
    }

    private static CreatePaymentRequest offSessionBody(String pm) {
        return new CreatePaymentRequest(5000L, "USD", null, null, null, null, null, null, null, null,
                /* payment_method */ pm, /* off_session */ Boolean.TRUE,
                /* setup_future_usage */ "off_session", /* mandate_id */ "m_1");
    }

    private static CreatePaymentRequest inlineBody() {
        return new CreatePaymentRequest(5000L, "USD", "cus_1", "card", null, null, null,
                "automatic", null, null, null, null, null, null);
    }

    private static PaymentResponse ok() {
        return new PaymentResponse("pay_test_1", PaymentResponse.STATUS_SUCCEEDED, 5000L, "USD",
                "automatic", "cus_1", "mock", "txn_1", null, null, Instant.now(), Map.of());
    }

    // --- delegation: pm_ present -> OffSessionChargeService with principal tenant + server-derived isTest ---

    @Test
    void pmPresent_testKey_delegatesWithTenantAndIsTestTrue() {
        when(offSession.charge(anyString(), anyString(), anyLong(), anyString(),
                any(), any(), any(), anyBoolean(), any(), any())).thenReturn(ok());

        controller.createPayment(offSessionBody("pm_123"), "idem-1", null, testPrincipal);

        verify(offSession).charge(eq("t1"), eq("pm_123"), eq(5000L), eq("USD"),
                eq(Boolean.TRUE), eq("off_session"), eq("m_1"),
                eq(true), eq("idem-1"), any());
        // The inline path is NOT taken.
        verifyNoInteractions(gateway);
    }

    @Test
    void pmPresent_liveKey_delegatesWithIsTestFalse() {
        when(offSession.charge(anyString(), anyString(), anyLong(), anyString(),
                any(), any(), any(), anyBoolean(), any(), any())).thenReturn(ok());

        controller.createPayment(offSessionBody("pm_123"), "idem-1", null, livePrincipal);

        verify(offSession).charge(eq("t1"), eq("pm_123"), anyLong(), anyString(),
                any(), any(), any(), eq(false), any(), any());
    }

    // --- service errors propagate (GlobalExceptionHandler maps 404/400 in the real app) ---

    @Test
    void foreignPm_404_propagates_andNeverCharges() {
        when(offSession.charge(anyString(), anyString(), anyLong(), anyString(),
                any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new ResourceNotFoundException("Payment method not found"));

        assertThatThrownBy(() -> controller.createPayment(
                offSessionBody("pm_foreign"), "idem-1", null, testPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void livemodeMismatch_400_propagates() {
        when(offSession.charge(anyString(), anyString(), anyLong(), anyString(),
                any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new InvalidRequestException("livemode mismatch", "livemode_mismatch"));

        assertThatThrownBy(() -> controller.createPayment(
                offSessionBody("pm_123"), "idem-1", null, livePrincipal))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "livemode_mismatch");
    }

    // --- BACK-COMPAT: pm_ absent -> inline path, off-session service NOT invoked ---

    @Test
    void pmAbsent_inlinePath_doesNotInvokeOffSession_andForwardsNullOffSessionFields() {
        when(gateway.createPayment(any(PaymentRequest.class), any(CallContext.class))).thenReturn(ok());

        controller.createPayment(inlineBody(), "idem-1", null, livePrincipal);

        verifyNoInteractions(offSession);
        ArgumentCaptor<PaymentRequest> cap = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(gateway).createPayment(cap.capture(), any(CallContext.class));
        PaymentRequest req = cap.getValue();
        // Byte-identical to pre-3c: the four off-session fields are null on the inline path.
        assertThat(req.paymentMethod()).isNull();
        assertThat(req.offSession()).isNull();
        assertThat(req.setupFutureUsage()).isNull();
        assertThat(req.mandateId()).isNull();
        assertThat(req.customerId()).isEqualTo("cus_1");
    }

    @Test
    void pmBlank_takesInlinePath() {
        when(gateway.createPayment(any(PaymentRequest.class), any(CallContext.class))).thenReturn(ok());
        // A blank payment_method must NOT trigger delegation (treated as absent).
        var blank = new CreatePaymentRequest(5000L, "USD", "cus_1", "card", null, null, null,
                "automatic", null, null, "   ", null, null, null);

        controller.createPayment(blank, "idem-1", null, livePrincipal);

        verifyNoInteractions(offSession);
        verify(gateway).createPayment(any(PaymentRequest.class), any(CallContext.class));
    }
}
