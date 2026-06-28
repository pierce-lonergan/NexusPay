package io.nexuspay.payment.application.service;

import io.nexuspay.common.exception.InvalidRequestException;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.payment.adapter.out.mock.MockPaymentGatewayPort;
import io.nexuspay.payment.adapter.out.mock.TestPaymentMethodFixtures;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.CallContext;
import io.nexuspay.payment.application.service.mandate.MandateService;
import io.nexuspay.payment.application.service.paymentmethod.PaymentMethodService;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.paymentmethod.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TEST-3c: unit tests for {@link OffSessionChargeService} — the SINGLE off-session orchestration point.
 *
 * <p>Asserts the load-bearing money/tenant invariants: a synthetic-fixture {@code pm_card_visa} succeeds
 * and forwards offSession + the resolved customer + the opaque credentialRef as {@code paymentMethod} with
 * NO {@code __test_outcome}; {@code pm_card_chargeDeclined} injects {@code __test_outcome=declined} and the
 * real mock returns {@code STATUS_FAILED}; a cross-tenant/missing {@code pm_} 404s with the gateway never
 * called; a livemode mismatch 400s with the gateway never called.</p>
 *
 * <p>L-071: an ObjectMapper is not needed here (no JSON), and we never assert a hardcoded server-generated
 * id (we assert the forwarded request fields, not the mock's minted {@code pay_test_*} id).</p>
 */
class OffSessionChargeServiceTest {

    private static final String TENANT_A = "tenant_a";
    private static final String CUSTOMER = "cus_off_1";

    /** Builds a saved method with a precise credentialRef + livemode (setters give full control). */
    private static PaymentMethod pm(String id, String tenant, boolean livemode, String credentialRef) {
        PaymentMethod p = new PaymentMethod();
        p.setId(id);
        p.setTenantId(tenant);
        p.setCustomerId(CUSTOMER);
        p.setLivemode(livemode);
        p.setType("card");
        p.setCredentialRef(credentialRef);
        return p;
    }

    private static String synthetic(String token) {
        return TestPaymentMethodFixtures.SYNTHETIC_REF_PREFIX + token;
    }

    // (1) pm_card_visa (TEST, synthetic ref) -> SUCCESS; forwarded request carries offSession + customer +
    // credentialRef as paymentMethod, and NO __test_outcome.
    @Test
    void visaTestFixture_succeeds_andForwardsOffSessionFields() {
        var pmService = mock(PaymentMethodService.class);
        // A capturing real mock as the gateway so the success path (STATUS_SUCCEEDED) is genuine.
        var gateway = new MockPaymentGatewayPort();
        var capturing = mock(PaymentGatewayPort.class);
        String credRef = synthetic("pm_card_visa");
        when(pmService.findById("pm_visa", TENANT_A))
                .thenReturn(Optional.of(pm("pm_visa", TENANT_A, false, credRef)));
        // Delegate to the real mock so the response is a genuine success, while capturing the request.
        when(capturing.createPayment(any(PaymentRequest.class), any(CallContext.class)))
                .thenAnswer(inv -> gateway.createPayment(inv.getArgument(0)));

        var mandateService = mock(MandateService.class);
        var svc = new OffSessionChargeService(pmService, mandateService, capturing);
        PaymentResponse resp = svc.charge(TENANT_A, "pm_visa", 5000, "USD",
                Boolean.TRUE, "off_session", null, /* isTest */ true, "idem-1",
                Map.of("userId", "u1"));

        assertThat(resp.status()).isEqualTo(PaymentResponse.STATUS_SUCCEEDED);

        ArgumentCaptor<PaymentRequest> cap = ArgumentCaptor.forClass(PaymentRequest.class);
        ArgumentCaptor<CallContext> ctx = ArgumentCaptor.forClass(CallContext.class);
        verify(capturing).createPayment(cap.capture(), ctx.capture());
        PaymentRequest req = cap.getValue();
        assertThat(req.offSession()).isTrue();
        assertThat(req.setupFutureUsage()).isEqualTo("off_session");
        assertThat(req.customerId()).isEqualTo(CUSTOMER);
        assertThat(req.paymentMethod()).isEqualTo(credRef); // opaque handle, NOT a raw PAN
        assertThat(req.paymentMethodData()).isNull();       // never raw card data
        assertThat(req.metadata()).doesNotContainKey(MockPaymentGatewayPort.TEST_OUTCOME_KEY);
        // Interactive rail — an API-initiated off-session charge is merchant-present.
        assertThat(ctx.getValue().tenantId()).isEqualTo(TENANT_A);
        // (3d back-compat) no mandate_id cited -> the consent gate is never consulted.
        verify(mandateService, never()).validateActiveForCharge(any(), any(), any());
    }

    // (2) pm_card_chargeDeclined (TEST) -> service injects __test_outcome=declined, real mock returns FAILED.
    @Test
    void declineTestFixture_injectsOutcome_andMockFails() {
        var pmService = mock(PaymentMethodService.class);
        var gateway = new MockPaymentGatewayPort();
        var capturing = mock(PaymentGatewayPort.class);
        String credRef = synthetic("pm_card_chargeDeclined");
        when(pmService.findById("pm_dec", TENANT_A))
                .thenReturn(Optional.of(pm("pm_dec", TENANT_A, false, credRef)));
        when(capturing.createPayment(any(PaymentRequest.class), any(CallContext.class)))
                .thenAnswer(inv -> gateway.createPayment(inv.getArgument(0)));

        var mandateService = mock(MandateService.class);
        var svc = new OffSessionChargeService(pmService, mandateService, capturing);
        PaymentResponse resp = svc.charge(TENANT_A, "pm_dec", 5000, "USD",
                Boolean.TRUE, null, null, /* isTest */ true, "idem-2", null);

        assertThat(resp.status()).isEqualTo(PaymentResponse.STATUS_FAILED);
        assertThat(resp.errorCode()).isEqualTo("card_declined");

        ArgumentCaptor<PaymentRequest> cap = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(capturing).createPayment(cap.capture(), any(CallContext.class));
        assertThat(cap.getValue().metadata())
                .containsEntry(MockPaymentGatewayPort.TEST_OUTCOME_KEY, "declined");
    }

    // (3) cross-tenant / missing pm_ -> 404 no-oracle; gateway NEVER called.
    @Test
    void crossTenantOrMissingPm_404s_andNeverCharges() {
        var pmService = mock(PaymentMethodService.class);
        var gateway = mock(PaymentGatewayPort.class);
        // tenant B's view of tenant A's pm_ is empty (findByIdAndTenantId predicate).
        when(pmService.findById("pm_foreign", "tenant_b")).thenReturn(Optional.empty());

        var mandateService = mock(MandateService.class);
        var svc = new OffSessionChargeService(pmService, mandateService, gateway);
        assertThatThrownBy(() -> svc.charge("tenant_b", "pm_foreign", 5000, "USD",
                Boolean.TRUE, null, null, true, "idem-3", null))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(gateway);
    }

    // (4a) livemode mismatch: a TEST pm_ (livemode=false) under a LIVE caller (isTest=false) -> 400, no charge.
    @Test
    void testPmUnderLiveKey_400s_andNeverCharges() {
        var pmService = mock(PaymentMethodService.class);
        var gateway = mock(PaymentGatewayPort.class);
        when(pmService.findById("pm_visa", TENANT_A))
                .thenReturn(Optional.of(pm("pm_visa", TENANT_A, false, synthetic("pm_card_visa"))));

        var mandateService = mock(MandateService.class);
        var svc = new OffSessionChargeService(pmService, mandateService, gateway);
        assertThatThrownBy(() -> svc.charge(TENANT_A, "pm_visa", 5000, "USD",
                Boolean.TRUE, null, null, /* isTest */ false, "idem-4", null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("livemode");

        verify(gateway, never()).createPayment(any(), any());
        verify(gateway, never()).createPayment(any());
    }

    // (4b) inverse: a LIVE pm_ (livemode=true) under a TEST key (isTest=true) -> 400, no charge.
    @Test
    void livePmUnderTestKey_400s_andNeverCharges() {
        var pmService = mock(PaymentMethodService.class);
        var gateway = mock(PaymentGatewayPort.class);
        when(pmService.findById("pm_live", TENANT_A))
                .thenReturn(Optional.of(pm("pm_live", TENANT_A, true, "ptok_live_abc123")));

        var mandateService = mock(MandateService.class);
        var svc = new OffSessionChargeService(pmService, mandateService, gateway);
        assertThatThrownBy(() -> svc.charge(TENANT_A, "pm_live", 5000, "USD",
                Boolean.TRUE, null, null, /* isTest */ true, "idem-5", null))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "livemode_mismatch");

        verifyNoInteractions(gateway);
    }

    // ---- TEST-3d: a cited mandate is a real consent gate ----

    private static final String MANDATE = "mandate_ok";

    // (5) ACTIVE mandate for the SAME pm_ -> proceeds (gateway called, success).
    @Test
    void citedActiveMandate_samePm_proceeds() {
        var pmService = mock(PaymentMethodService.class);
        var mandateService = mock(MandateService.class);
        var gateway = new MockPaymentGatewayPort();
        var capturing = mock(PaymentGatewayPort.class);
        String credRef = synthetic("pm_card_visa");
        when(pmService.findById("pm_visa", TENANT_A))
                .thenReturn(Optional.of(pm("pm_visa", TENANT_A, false, credRef)));
        when(capturing.createPayment(any(PaymentRequest.class), any(CallContext.class)))
                .thenAnswer(inv -> gateway.createPayment(inv.getArgument(0)));

        var svc = new OffSessionChargeService(pmService, mandateService, capturing);
        PaymentResponse resp = svc.charge(TENANT_A, "pm_visa", 5000, "USD",
                Boolean.TRUE, "off_session", MANDATE, /* isTest */ true, "idem-6", null);

        assertThat(resp.status()).isEqualTo(PaymentResponse.STATUS_SUCCEEDED);
        // The gate was consulted with the trusted tenant-owned pm id and the cited mandate.
        verify(mandateService).validateActiveForCharge(MANDATE, TENANT_A, "pm_visa");
        verify(capturing).createPayment(any(PaymentRequest.class), any(CallContext.class));
    }

    // (6) FOREIGN/missing mandate -> ResourceNotFoundException, gateway NEVER called.
    @Test
    void citedForeignMandate_404s_andNeverCharges() {
        var pmService = mock(PaymentMethodService.class);
        var mandateService = mock(MandateService.class);
        var gateway = mock(PaymentGatewayPort.class);
        when(pmService.findById("pm_visa", TENANT_A))
                .thenReturn(Optional.of(pm("pm_visa", TENANT_A, false, synthetic("pm_card_visa"))));
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Mandate not found"))
                .when(mandateService).validateActiveForCharge("mandate_foreign", TENANT_A, "pm_visa");

        var svc = new OffSessionChargeService(pmService, mandateService, gateway);
        assertThatThrownBy(() -> svc.charge(TENANT_A, "pm_visa", 5000, "USD",
                Boolean.TRUE, null, "mandate_foreign", true, "idem-7", null))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(gateway);
    }

    // (7) INACTIVE mandate -> InvalidRequestException invalid_mandate, gateway never called.
    @Test
    void citedInactiveMandate_400s_andNeverCharges() {
        var pmService = mock(PaymentMethodService.class);
        var mandateService = mock(MandateService.class);
        var gateway = mock(PaymentGatewayPort.class);
        when(pmService.findById("pm_visa", TENANT_A))
                .thenReturn(Optional.of(pm("pm_visa", TENANT_A, false, synthetic("pm_card_visa"))));
        org.mockito.Mockito.doThrow(new InvalidRequestException("Mandate is not active", "invalid_mandate"))
                .when(mandateService).validateActiveForCharge("mandate_dead", TENANT_A, "pm_visa");

        var svc = new OffSessionChargeService(pmService, mandateService, gateway);
        assertThatThrownBy(() -> svc.charge(TENANT_A, "pm_visa", 5000, "USD",
                Boolean.TRUE, null, "mandate_dead", true, "idem-8", null))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "invalid_mandate");

        verifyNoInteractions(gateway);
    }

    // (8) mandate authorizes a DIFFERENT pm_ -> mandate_payment_method_mismatch, gateway never called.
    @Test
    void citedMandate_pmMismatch_400s_andNeverCharges() {
        var pmService = mock(PaymentMethodService.class);
        var mandateService = mock(MandateService.class);
        var gateway = mock(PaymentGatewayPort.class);
        when(pmService.findById("pm_visa", TENANT_A))
                .thenReturn(Optional.of(pm("pm_visa", TENANT_A, false, synthetic("pm_card_visa"))));
        org.mockito.Mockito.doThrow(new InvalidRequestException(
                        "Mandate does not authorize this payment method", "mandate_payment_method_mismatch"))
                .when(mandateService).validateActiveForCharge("mandate_other_pm", TENANT_A, "pm_visa");

        var svc = new OffSessionChargeService(pmService, mandateService, gateway);
        assertThatThrownBy(() -> svc.charge(TENANT_A, "pm_visa", 5000, "USD",
                Boolean.TRUE, null, "mandate_other_pm", true, "idem-9", null))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "mandate_payment_method_mismatch");

        verifyNoInteractions(gateway);
    }

    // (9) back-compat: a BLANK mandate_id stays the 3c pass-through (gate never consulted, success).
    @Test
    void blankMandateId_isPassThrough_gateNeverConsulted() {
        var pmService = mock(PaymentMethodService.class);
        var mandateService = mock(MandateService.class);
        var gateway = new MockPaymentGatewayPort();
        var capturing = mock(PaymentGatewayPort.class);
        when(pmService.findById("pm_visa", TENANT_A))
                .thenReturn(Optional.of(pm("pm_visa", TENANT_A, false, synthetic("pm_card_visa"))));
        when(capturing.createPayment(any(PaymentRequest.class), any(CallContext.class)))
                .thenAnswer(inv -> gateway.createPayment(inv.getArgument(0)));

        var svc = new OffSessionChargeService(pmService, mandateService, capturing);
        PaymentResponse resp = svc.charge(TENANT_A, "pm_visa", 5000, "USD",
                Boolean.TRUE, null, "   ", /* isTest */ true, "idem-10", null);

        assertThat(resp.status()).isEqualTo(PaymentResponse.STATUS_SUCCEEDED);
        verify(mandateService, never()).validateActiveForCharge(any(), any(), any());
    }
}
