package io.nexuspay.payment.application.screening;

import io.nexuspay.common.mode.PaymentMode;
import io.nexuspay.payment.adapter.out.hyperswitch.HyperSwitchPaymentAdapter;
import io.nexuspay.payment.adapter.out.mock.MockPaymentGatewayPort;
import io.nexuspay.payment.adapter.out.mock.TestPaymentMethodFixtures;
import io.nexuspay.payment.application.service.OffSessionChargeService;
import io.nexuspay.payment.application.service.mandate.MandateService;
import io.nexuspay.payment.application.service.paymentmethod.PaymentMethodService;
import io.nexuspay.payment.application.webhook.MockWebhookSynthesizer;
import io.nexuspay.payment.application.webhook.WebhookMetadataService;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.event.PaymentEvent;
import io.nexuspay.payment.domain.paymentmethod.PaymentMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TEST-3c: an off-session charge reuses the SAME outbox/webhook side-effects as the inline-card create.
 *
 * <p>Drives the FULL off-session path: {@link OffSessionChargeService} resolves a TEST fixture {@code pm_}
 * and calls the REAL {@link GatedPaymentGateway} (TEST mode routes to the REAL {@link
 * MockPaymentGatewayPort}). Asserts a {@code pm_card_visa} success synthesizes {@code payment.captured} and
 * a {@code pm_card_chargeDeclined} (decoded -> {@code __test_outcome=declined}) synthesizes
 * {@code payment.failed} — the same {@link MockWebhookSynthesizer} the inline-card mock create uses. The
 * fraud/sanctions gate is NEVER invoked on the TEST rail (the mock moves no real money), exactly like the
 * inline mock create.</p>
 */
class GatedPaymentGatewayOffSessionWebhookTest {

    private static final String TENANT = "tenant_a";
    private static final String CUSTOMER = "cus_off_1";

    private HyperSwitchPaymentAdapter hyperSwitch;
    private MockPaymentGatewayPort realMock;
    private PreAuthorizationGate gate;
    private CaptureHoldService holds;
    private ScreeningOriginService origins;
    private WebhookMetadataService webhookMetadata;
    private MockWebhookSynthesizer synthesizer;
    private GatedPaymentGateway gateway;

    private PaymentMethodService pmService;
    private OffSessionChargeService offSession;

    @BeforeEach
    void setUp() {
        hyperSwitch = mock(HyperSwitchPaymentAdapter.class);
        realMock = new MockPaymentGatewayPort(); // REAL deterministic mock so forced-decline path runs.
        gate = mock(PreAuthorizationGate.class);
        holds = mock(CaptureHoldService.class);
        origins = mock(ScreeningOriginService.class);
        webhookMetadata = mock(WebhookMetadataService.class);
        synthesizer = mock(MockWebhookSynthesizer.class);
        lenient().when(origins.find(any())).thenReturn(Optional.empty());
        gateway = new GatedPaymentGateway(hyperSwitch, realMock, gate, holds, origins,
                webhookMetadata, synthesizer);

        pmService = mock(PaymentMethodService.class);
        // TEST-3d: these cases all pass a null mandateId (back-compat 3c pass-through), so the mandate gate
        // is never consulted — a bare mock suffices.
        offSession = new OffSessionChargeService(pmService, mock(MandateService.class), gateway);

        // TEST rail: route every port op to the mock (the off-session charge is merchant-present but TEST).
        PaymentMode.set(false);
    }

    @AfterEach
    void tearDown() {
        PaymentMode.clear();
    }

    private static PaymentMethod pm(boolean livemode, String credentialRef) {
        PaymentMethod p = new PaymentMethod();
        p.setId("pm_x");
        p.setTenantId(TENANT);
        p.setCustomerId(CUSTOMER);
        p.setLivemode(livemode);
        p.setType("card");
        p.setCredentialRef(credentialRef);
        return p;
    }

    private static String synthetic(String token) {
        return TestPaymentMethodFixtures.SYNTHETIC_REF_PREFIX + token;
    }

    @Test
    void visaOffSession_success_synthesizesPaymentCaptured() {
        when(pmService.findById("pm_x", TENANT))
                .thenReturn(Optional.of(pm(false, synthetic("pm_card_visa"))));

        PaymentResponse resp = offSession.charge(TENANT, "pm_x", 5000, "USD",
                Boolean.TRUE, "off_session", null, /* isTest */ true, "idem-1", null);

        assertThat(resp.status()).isEqualTo(PaymentResponse.STATUS_SUCCEEDED);
        // Same side-effect as the inline mock create: payment.captured synthesized after origin+metadata.
        verify(synthesizer).onTerminal(any(PaymentResponse.class), any(), eq(PaymentEvent.PAYMENT_CAPTURED));
        verify(synthesizer, never()).onTerminalFailure(any(), any(), any());
        // TEST rail skips the money-out gate AND never touches HyperSwitch.
        verifyNoInteractions(gate);
        verifyNoInteractions(hyperSwitch);
    }

    @Test
    void declinedOffSession_synthesizesPaymentFailed() {
        when(pmService.findById("pm_x", TENANT))
                .thenReturn(Optional.of(pm(false, synthetic("pm_card_chargeDeclined"))));

        PaymentResponse resp = offSession.charge(TENANT, "pm_x", 5000, "USD",
                Boolean.TRUE, null, null, /* isTest */ true, "idem-2", null);

        assertThat(resp.status()).isEqualTo(PaymentResponse.STATUS_FAILED);
        // Forced decline -> payment.failed synthesized (the inline TEST-1 decline path, reused).
        verify(synthesizer).onTerminalFailure(any(PaymentResponse.class), any(),
                eq(PaymentEvent.PAYMENT_FAILED));
        verify(synthesizer, never()).onTerminal(any(), any(), any());
        verifyNoInteractions(gate);
        verifyNoInteractions(hyperSwitch);
    }
}
