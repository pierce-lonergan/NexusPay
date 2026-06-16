package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.gateway.adapter.in.rest.dto.CancelPaymentRequest;
import io.nexuspay.gateway.adapter.in.rest.dto.CapturePaymentRequest;
import io.nexuspay.gateway.adapter.in.rest.dto.ConfirmPaymentRequest;
import io.nexuspay.gateway.adapter.in.rest.dto.CreateRefundRequest;
import io.nexuspay.gateway.application.RefundOrchestrationService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.domain.CaptureRequest;
import io.nexuspay.payment.domain.ConfirmRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.VoidRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-07 (B-007): the payment-lifecycle endpoints must verify the caller's tenant OWNS the gateway
 * payment id BEFORE the id reaches the PSP. A tenant-A operator acting on a tenant-B-owned payment must
 * get a fail-closed 404 ({@link ResourceNotFoundException}, mapped to HTTP 404 by GlobalExceptionHandler)
 * and the PSP must NOT be touched.
 *
 * <p>Built by direct construction (the gateway-api controller-test convention, see
 * {@code ApprovalControllerTest}), asserting the thrown {@link ResourceNotFoundException} on the
 * controller method — the {@code @WebMvcTest} slice omits GlobalExceptionHandler, so this style is the
 * faithful 404 check. The cross-tenant case is modeled by having {@link ScreeningOriginService#assertOwnedBy}
 * throw for the foreign id (its real behavior is unit-tested in ScreeningOriginServiceAssertOwnedByTest).</p>
 *
 * <p>Each "cross-tenant -> 404" test FAILS if the {@code assertOwnedBy} call is removed from the
 * corresponding handler (the PSP would then be called and no exception thrown).</p>
 */
class PaymentControllerOwnershipTest {

    private PaymentGatewayPort gateway;
    private RefundOrchestrationService refundOrchestration;
    private ScreeningOriginService screeningOrigins;
    private PaymentController controller;

    private static final String TENANT_A = "tenant-A";
    private static final String OWNED = "pay_owned_by_A";
    private static final String FOREIGN = "pay_owned_by_B";

    private final NexusPayPrincipal operatorA =
            new NexusPayPrincipal("op_A", TENANT_A, "operator", NexusPayPrincipal.AuthMethod.JWT);

    @BeforeEach
    void setUp() {
        gateway = mock(PaymentGatewayPort.class);
        refundOrchestration = mock(RefundOrchestrationService.class);
        screeningOrigins = mock(ScreeningOriginService.class);
        controller = new PaymentController(gateway, refundOrchestration, screeningOrigins);

        // Caller (tenant-A) owns OWNED; assertOwnedBy is a no-op (default mock behavior on the void method).
        // Caller (tenant-A) does NOT own FOREIGN: assertOwnedBy throws the fail-closed 404.
        doThrow(ResourceNotFoundException.of("Payment", FOREIGN))
                .when(screeningOrigins).assertOwnedBy(eq(FOREIGN), anyString());
    }

    private PaymentResponse okResponse(String id) {
        return new PaymentResponse(id, PaymentResponse.STATUS_REQUIRES_CAPTURE, 1000, "USD",
                "manual", "cus_1", "stripe", "con_1", null, null, Instant.now(), Map.of());
    }

    // ---- cross-tenant -> 404, PSP never touched ----

    @Test
    void getPayment_crossTenant_404_pspNotCalled() {
        assertThatThrownBy(() -> controller.getPayment(FOREIGN, operatorA))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(gateway, never()).getPayment(anyString());
    }

    @Test
    void capturePayment_crossTenant_404_pspNotCalled() {
        assertThatThrownBy(() ->
                controller.capturePayment(FOREIGN, new CapturePaymentRequest(null), null, operatorA))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(gateway, never()).capturePayment(anyString(), any(CaptureRequest.class));
    }

    @Test
    void cancelPayment_crossTenant_404_pspNotCalled() {
        assertThatThrownBy(() ->
                controller.cancelPayment(FOREIGN, new CancelPaymentRequest("dup"), null, operatorA))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(gateway, never()).voidPayment(anyString(), any(VoidRequest.class));
    }

    @Test
    void confirmPayment_crossTenant_404_pspNotCalled() {
        assertThatThrownBy(() ->
                controller.confirmPayment(FOREIGN, new ConfirmPaymentRequest(null, null, null), null, operatorA))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(gateway, never()).confirmPayment(anyString(), any(ConfirmRequest.class), any());
    }

    /**
     * The sub-threshold (amount=49999 < 50000) cross-tenant refund is the headline B-007 bypass: it used
     * to route straight to the PSP with no ownership check. The controller delegates to
     * RefundOrchestrationService, whose createRefund now asserts ownership FIRST; here the mocked service
     * throws for the foreign id, and the controller must propagate the 404.
     */
    @Test
    void subThresholdRefund_crossTenant_404() {
        when(refundOrchestration.createRefund(eq(FOREIGN), eq(49999L), anyString(), anyString(),
                any(), anyString(), eq(TENANT_A)))
                .thenThrow(ResourceNotFoundException.of("Payment", FOREIGN));

        assertThatThrownBy(() -> controller.createRefund(
                FOREIGN, new CreateRefundRequest(49999L, "USD", "sneaky"), "client-key", operatorA))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- same-tenant -> delegates to PSP ----

    @Test
    void getPayment_sameTenant_delegatesToPsp() {
        when(gateway.getPayment(OWNED)).thenReturn(okResponse(OWNED));

        assertThatCode(() -> controller.getPayment(OWNED, operatorA)).doesNotThrowAnyException();

        verify(screeningOrigins).assertOwnedBy(OWNED, TENANT_A);
        verify(gateway).getPayment(OWNED);
    }

    @Test
    void capturePayment_sameTenant_delegatesToPsp() {
        when(gateway.capturePayment(eq(OWNED), any(CaptureRequest.class))).thenReturn(okResponse(OWNED));

        controller.capturePayment(OWNED, new CapturePaymentRequest(500L), "k", operatorA);

        verify(screeningOrigins).assertOwnedBy(OWNED, TENANT_A);
        verify(gateway).capturePayment(eq(OWNED), any(CaptureRequest.class));
    }

    @Test
    void cancelPayment_sameTenant_delegatesToPsp() {
        when(gateway.voidPayment(eq(OWNED), any(VoidRequest.class))).thenReturn(okResponse(OWNED));

        controller.cancelPayment(OWNED, new CancelPaymentRequest("requested"), "k", operatorA);

        verify(screeningOrigins).assertOwnedBy(OWNED, TENANT_A);
        verify(gateway).voidPayment(eq(OWNED), any(VoidRequest.class));
    }

    @Test
    void confirmPayment_sameTenant_delegatesToPsp() {
        when(gateway.confirmPayment(eq(OWNED), any(ConfirmRequest.class), any()))
                .thenReturn(okResponse(OWNED));

        controller.confirmPayment(OWNED, new ConfirmPaymentRequest("card", null, null), "k", operatorA);

        verify(screeningOrigins).assertOwnedBy(OWNED, TENANT_A);
        verify(gateway).confirmPayment(eq(OWNED), any(ConfirmRequest.class), any());
    }
}
