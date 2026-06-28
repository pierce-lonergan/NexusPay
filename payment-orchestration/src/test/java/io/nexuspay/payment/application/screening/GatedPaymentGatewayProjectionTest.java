package io.nexuspay.payment.application.screening;

import io.nexuspay.payment.adapter.out.hyperswitch.HyperSwitchPaymentAdapter;
import io.nexuspay.payment.adapter.out.mock.MockPaymentGatewayPort;
import io.nexuspay.payment.application.port.out.PaymentProjectionRepository;
import io.nexuspay.payment.application.port.out.RefundProjectionRepository;
import io.nexuspay.payment.application.service.projection.PaymentProjectionService;
import io.nexuspay.payment.application.webhook.MockWebhookSynthesizer;
import io.nexuspay.payment.application.webhook.WebhookMetadataService;
import io.nexuspay.payment.domain.CaptureRequest;
import io.nexuspay.payment.domain.ConfirmRequest;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.RefundResponse;
import io.nexuspay.payment.domain.VoidRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-076 (critique v3 F1): the ★ CARDINAL-RULE proof + the per-op projection-hook coverage for the
 * {@link GatedPaymentGateway} read-model write strategy (strategy a — sync at every site).
 *
 * <ul>
 *   <li><b>NON-BLOCKING (secondary check)</b>: a REAL {@link PaymentProjectionService} over a SYNCHRONOUSLY
 *       throwing repository does NOT fail {@code createPayment}/{@code createRefund}. NOTE: with no Spring
 *       context this only proves the in-body throw is swallowed — it does NOT exercise the REQUIRES_NEW
 *       PROXY COMMIT, which is the production-critical failure mode (a constraint/flush error surfacing at
 *       commit, OUTSIDE the inner tx body). That COMMIT-TIME swallow — the actual cardinal-rule proof — is
 *       in {@code app}'s {@code PaymentProjectionReadModelIntegrationTest} (a real null-status row that
 *       violates NOT NULL at the REQUIRES_NEW commit and is swallowed by the OUTER, non-transactional
 *       {@link PaymentProjectionService} wrapper). This class keeps the synchronous-throw case as the
 *       lighter, Docker-free secondary check.</li>
 *   <li><b>HOOK COVERAGE</b>: with a MOCK service, {@code record}/{@code recordRefund} is invoked at
 *       create/confirm/capture/void/createRefund with the right tenant + livemode arg (mock branch ->
 *       livemode=false).</li>
 * </ul>
 *
 * <p>These cases drive the MOCK branch via {@code ctx.live()==FALSE} (the sk_test_ route), so they run on a
 * plain unit thread without a servlet request. The mock + projection are Mockito mocks.</p>
 */
class GatedPaymentGatewayProjectionTest {

    private HyperSwitchPaymentAdapter delegate;
    private MockPaymentGatewayPort mockDelegate;
    private PreAuthorizationGate gate;
    private CaptureHoldService holds;
    private ScreeningOriginService origins;
    private WebhookMetadataService webhookMetadata;
    private MockWebhookSynthesizer synthesizer;
    private PaymentProjectionService projection;
    private GatedPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        delegate = mock(HyperSwitchPaymentAdapter.class);
        mockDelegate = mock(MockPaymentGatewayPort.class);
        gate = mock(PreAuthorizationGate.class);
        holds = mock(CaptureHoldService.class);
        origins = mock(ScreeningOriginService.class);
        webhookMetadata = mock(WebhookMetadataService.class);
        synthesizer = mock(MockWebhookSynthesizer.class);
        projection = mock(PaymentProjectionService.class);
        // origin store supplies the trusted tenant for capture/void/refund hooks.
        lenient().when(origins.find(any()))
                .thenReturn(Optional.of(new ScreeningOriginService.Origin("tenant-A", ScreeningMode.INTERACTIVE)));
        gateway = new GatedPaymentGateway(delegate, mockDelegate, gate, holds, origins, webhookMetadata,
                synthesizer, projection);
    }

    @AfterEach
    void clearMode() {
        io.nexuspay.common.mode.PaymentMode.clear();
    }

    /** ctx.live()==FALSE forces the mock route (test-mode, livemode=false). */
    private static CallContext testMode() {
        return new CallContext("tenant-A", ScreeningMode.INTERACTIVE, Boolean.FALSE);
    }

    private static PaymentRequest req() {
        return new PaymentRequest(5000, "USD", "cust_1", "card", "4111111111111111",
                null, "desc", "automatic", "idem-1", Map.of());
    }

    private static PaymentResponse mockResp(String id, String status) {
        return new PaymentResponse(id, status, 5000, "USD", "automatic", "cust_1", "mock",
                "txn_test_1", null, null, Instant.EPOCH, Map.of());
    }

    // ---- ★ NON-BLOCKING: a projection-repo throw does NOT fail the create (swallow lives in the service) ----

    /** Builds a gateway wired with a REAL projection service whose repos THROW, to prove the swallow. */
    private GatedPaymentGateway gatewayWithThrowingProjection() {
        PaymentProjectionRepository payRepo = mock(PaymentProjectionRepository.class);
        RefundProjectionRepository refRepo = mock(RefundProjectionRepository.class);
        doThrow(new RuntimeException("db down")).when(payRepo).upsert(any());
        doThrow(new RuntimeException("db down")).when(refRepo).upsert(any());
        PaymentProjectionService realProjection = new PaymentProjectionService(
                new io.nexuspay.payment.application.service.projection.PaymentProjectionTxWriter(payRepo, refRepo));
        return new GatedPaymentGateway(delegate, mockDelegate, gate, holds, origins, webhookMetadata,
                synthesizer, realProjection);
    }

    @Test
    void createPayment_projectionRepoThrows_isSwallowed_createStillSucceeds() {
        when(mockDelegate.createPayment(any())).thenReturn(mockResp("pay_test_1", PaymentResponse.STATUS_SUCCEEDED));
        GatedPaymentGateway g = gatewayWithThrowingProjection();

        PaymentResponse[] result = new PaymentResponse[1];
        assertThatCode(() -> result[0] = g.createPayment(req(), testMode()))
                .doesNotThrowAnyException();

        // The create completed and returned the mock's response — the projection throw was swallowed.
        assertThat(result[0]).isNotNull();
        assertThat(result[0].gatewayPaymentId()).isEqualTo("pay_test_1");
        assertThat(result[0].status()).isEqualTo(PaymentResponse.STATUS_SUCCEEDED);
    }

    @Test
    void createRefund_projectionRepoThrows_isSwallowed_refundStillReturned() {
        RefundResponse refund = new RefundResponse("re_test_1", "pay_test_1",
                RefundResponse.STATUS_SUCCEEDED, 2500, "USD", null, "mock", "rc_0", null, null, Instant.EPOCH);
        when(mockDelegate.createRefund(any())).thenReturn(refund);
        GatedPaymentGateway g = gatewayWithThrowingProjection();

        RefundResponse[] result = new RefundResponse[1];
        assertThatCode(() -> result[0] = g.createRefund(
                new RefundRequest("pay_test_1", 2500L, "USD", null, "k")))
                .doesNotThrowAnyException();

        assertThat(result[0]).isNotNull();
        assertThat(result[0].gatewayRefundId()).isEqualTo("re_test_1");
    }

    // ---- HOOK COVERAGE: each op records into the projection with tenant + livemode=false ----

    @Test
    void createPayment_recordsBirthState_withTenantAndLivemodeFalse() {
        when(mockDelegate.createPayment(any()))
                .thenReturn(mockResp("pay_test_2", PaymentResponse.STATUS_REQUIRES_ACTION));

        gateway.createPayment(req(), testMode());

        verify(projection).record(any(PaymentResponse.class), eq("tenant-A"), eq(false));
    }

    @Test
    void confirmPayment_recordsState() {
        when(mockDelegate.confirmPayment(eq("pay_test_3"), any()))
                .thenReturn(mockResp("pay_test_3", PaymentResponse.STATUS_REQUIRES_CAPTURE));

        gateway.confirmPayment("pay_test_3", new ConfirmRequest(null, null, null, "k"));

        verify(projection).record(any(PaymentResponse.class), eq("tenant-A"), eq(false));
    }

    @Test
    void capturePayment_recordsState() {
        when(mockDelegate.capturePayment(eq("pay_test_4"), any()))
                .thenReturn(mockResp("pay_test_4", PaymentResponse.STATUS_SUCCEEDED));

        gateway.capturePayment("pay_test_4", new CaptureRequest(5000L, "k"));

        verify(projection).record(any(PaymentResponse.class), eq("tenant-A"), eq(false));
    }

    @Test
    void voidPayment_recordsState() {
        when(mockDelegate.voidPayment(eq("pay_test_5"), any()))
                .thenReturn(mockResp("pay_test_5", PaymentResponse.STATUS_CANCELLED));

        gateway.voidPayment("pay_test_5", new VoidRequest("reason", "k"));

        verify(projection).record(any(PaymentResponse.class), eq("tenant-A"), eq(false));
    }

    @Test
    void createRefund_recordsRefund_withTenantAndLivemodeFalse() {
        RefundResponse refund = new RefundResponse("re_test_6", "pay_test_6",
                RefundResponse.STATUS_SUCCEEDED, 2500, "USD", "requested_by_customer", "mock",
                "rc_1", null, null, Instant.EPOCH);
        when(mockDelegate.createRefund(any())).thenReturn(refund);

        gateway.createRefund(new RefundRequest("pay_test_6", 2500L, "USD", "requested_by_customer", "k"));

        verify(projection).recordRefund(any(RefundResponse.class), eq("tenant-A"), eq(false));
    }
}
