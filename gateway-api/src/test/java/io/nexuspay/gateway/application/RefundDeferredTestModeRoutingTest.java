package io.nexuspay.gateway.application;

import io.nexuspay.common.mode.PaymentMode;
import io.nexuspay.iam.domain.PendingApproval;
import io.nexuspay.payment.adapter.out.hyperswitch.HyperSwitchPaymentAdapter;
import io.nexuspay.payment.adapter.out.mock.MockPaymentGatewayPort;
import io.nexuspay.payment.application.screening.CaptureHoldService;
import io.nexuspay.payment.application.screening.GatedPaymentGateway;
import io.nexuspay.payment.application.screening.PreAuthorizationGate;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.application.webhook.MockWebhookSynthesizer;
import io.nexuspay.payment.application.webhook.WebhookMetadataService;
import io.nexuspay.payment.domain.RefundResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * INT-3 BLOCKER: an above-threshold refund of a TEST ({@code pay_test_*}) payment must NEVER reach
 * {@link HyperSwitchPaymentAdapter} through the DEFERRED maker-checker paths — the
 * {@code RefundReconciler}'s SYSTEM thread (mode UNSET → resolves LIVE) or {@code ApprovalController}'s
 * approver request thread (a console/OIDC actor → LIVE). The original request thread's test
 * {@code PaymentMode} is gone by execution time, so the guarantee must hold via (a) the originating mode
 * captured into the approval payload and re-applied by {@link RefundOrchestrationService#executeApprovedRefund}
 * and (b) the {@link GatedPaymentGateway} {@code pay_test_} id fail-safe.
 *
 * <p>Wires the REAL orchestration service + REAL gateway (real mock delegate); only HyperSwitch is mocked
 * so ANY interaction is a guarantee violation. FAILS if either defense regresses.</p>
 */
class RefundDeferredTestModeRoutingTest {

    private HyperSwitchPaymentAdapter hyperSwitch;
    private GatedPaymentGateway gateway;
    private RefundOrchestrationService orchestration;

    @BeforeEach
    void setUp() {
        hyperSwitch = mock(HyperSwitchPaymentAdapter.class);
        MockPaymentGatewayPort mockDelegate = new MockPaymentGatewayPort();
        PreAuthorizationGate gate = mock(PreAuthorizationGate.class);
        CaptureHoldService holds = mock(CaptureHoldService.class);
        ScreeningOriginService origins = mock(ScreeningOriginService.class);
        WebhookMetadataService webhookMetadata = mock(WebhookMetadataService.class);
        MockWebhookSynthesizer synthesizer = mock(MockWebhookSynthesizer.class);
        lenient().when(origins.find(any())).thenReturn(Optional.empty());
        gateway = new GatedPaymentGateway(hyperSwitch, mockDelegate, gate, holds, origins,
                webhookMetadata, synthesizer,
                mock(io.nexuspay.payment.application.service.projection.PaymentProjectionService.class));
        orchestration = new RefundOrchestrationService(gateway, mock(io.nexuspay.iam.application.ApprovalService.class),
                origins, 50000L);

        PaymentMode.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void tearDown() {
        PaymentMode.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    /** Mirrors the payload RefundOrchestrationService.createRefund stamps for a test-payment refund. */
    private PendingApproval testRefundApproval() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_id", "pay_test_deferred");
        payload.put("amount", 60000L);
        payload.put("currency", "USD");
        payload.put("reason", "approved");
        payload.put("is_live", false); // captured originating mode (test)
        return new PendingApproval("appr_1", "refund", "Payment", "pay_test_deferred",
                payload, "APPROVED", "maker", "checker", "t1", Instant.now(), Instant.now());
    }

    @Test
    void reconcilerContext_systemThread_testRefund_neverReachesHyperSwitch() {
        // RefundReconciler.reconcileOne runs on a @Scheduled @SystemTransactional thread: no servlet
        // request bound, PaymentMode UNSET.
        PaymentMode.clear();
        RequestContextHolder.resetRequestAttributes();

        RefundResponse r = orchestration.executeApprovedRefund(testRefundApproval());

        assertThat(r.gatewayRefundId()).startsWith("re_test_"); // minted by the mock
        verifyNoInteractions(hyperSwitch);
        // The system thread is left UNSET (no mode leaked by the executor).
        assertThat(PaymentMode.isUnset()).isTrue();
    }

    @Test
    void approverContext_liveRequestThread_testRefund_neverReachesHyperSwitch() {
        // ApprovalController.approve runs on the approver's request thread; a Keycloak/OIDC console admin
        // resolves LIVE.
        PaymentMode.set(true);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        RefundResponse r = orchestration.executeApprovedRefund(testRefundApproval());

        assertThat(r.gatewayRefundId()).startsWith("re_test_");
        verifyNoInteractions(hyperSwitch);
        // The approver's own LIVE mode is restored after the deferred execution (not corrupted to test).
        assertThat(PaymentMode.isLiveExplicit()).isTrue();
    }
}
