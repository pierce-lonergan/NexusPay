package io.nexuspay.payment.application.screening;

import io.nexuspay.payment.adapter.out.hyperswitch.HyperSwitchPaymentAdapter;
import io.nexuspay.payment.adapter.out.mock.MockPaymentGatewayPort;
import io.nexuspay.payment.application.port.out.TestClockRepository;
import io.nexuspay.payment.application.service.clock.TestClockService;
import io.nexuspay.payment.application.service.projection.PaymentProjectionService;
import io.nexuspay.payment.application.webhook.MockWebhookSynthesizer;
import io.nexuspay.payment.application.webhook.WebhookMetadataService;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.RefundResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-078 (critique v3 F5): proves the per-tenant TEST CLOCK re-stamps the {@code createdAt} on TEST-created
 * artifacts at the {@link GatedPaymentGateway} mock seam — and ONLY there.
 *
 * <ul>
 *   <li>★ CORE: with the clock frozen for tenant-A, a MOCK create's response passed to
 *       {@code projection.record} carries {@code createdAt == FIXED} (so the GAP-076 read-model — which
 *       inherits created_at from the response — and its list ordering are deterministic). Symmetric for a
 *       mock refund (recordRefund arg createdAt == FIXED).</li>
 *   <li>★ LIVE ISOLATION: a LIVE charge (live delegate branch) records the REAL delegate createdAt, NOT
 *       FIXED — and the clock is NEVER consulted on the live branch (the repo finder is never hit for it).</li>
 *   <li>★ TENANT ISOLATION: tenant-A is frozen, tenant-B is not; a create under tenant-B uses real time
 *       (A's clock does not bleed to B).</li>
 *   <li>★ READ-PATH CONSISTENCY (GAP-078 SHOULD_FIX): the gateway also re-stamps the MOCK STORE (so a later
 *       single-retrieve GET /v1/payments/{id} | /v1/refunds/{id} returns the SAME frozen instant as the
 *       create response + the list) — proven by verifying {@code mockDelegate.restampCreatedAt(id, FIXED)};
 *       and the live path NEVER re-stamps the store.</li>
 * </ul>
 *
 * <p>The mock builds {@code createdAt = Instant.EPOCH} (a sentinel that is clearly neither the frozen value
 * nor real "now"), so an assertion of {@code == FIXED} unambiguously proves the re-stamp happened, and an
 * assertion of {@code != FIXED} on the live path proves it did not. L-071: no ObjectMapper / server-gen id
 * is asserted.</p>
 */
class GatedPaymentGatewayTestClockTest {

    private static final String TENANT_A = "tenant-A";
    private static final String TENANT_B = "tenant-B";
    private static final Instant FIXED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant MOCK_BUILT = Instant.EPOCH; // the mock's own stamp (neither FIXED nor now)
    private static final Instant LIVE_BUILT = Instant.parse("2025-03-03T03:03:03Z"); // the real PSP's stamp

    private HyperSwitchPaymentAdapter delegate;
    private MockPaymentGatewayPort mockDelegate;
    private PreAuthorizationGate gate;
    private CaptureHoldService holds;
    private ScreeningOriginService origins;
    private WebhookMetadataService webhookMetadata;
    private MockWebhookSynthesizer synthesizer;
    private PaymentProjectionService projection;
    private TestClockRepository clockRepo;
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
        clockRepo = mock(TestClockRepository.class);

        // The gate decision is irrelevant on the mock branches; for the LIVE test give a clean pass-through
        // (holdCapture=false, ALLOW) so the charge captures clean and the delegate's createdAt is preserved.
        lenient().when(gate.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(new GateDecision(false, io.nexuspay.fraud.domain.model.RiskDecision.ALLOW,
                        java.util.UUID.randomUUID(), false, false));
        // Origin store supplies the trusted tenant for refund/capture/void hooks.
        lenient().when(origins.find(any()))
                .thenReturn(Optional.of(new ScreeningOriginService.Origin(TENANT_A, ScreeningMode.INTERACTIVE)));

        gateway = new GatedPaymentGateway(delegate, mockDelegate, gate, holds, origins, webhookMetadata,
                synthesizer, projection, new TestClockService(clockRepo));
    }

    @AfterEach
    void clearMode() {
        io.nexuspay.common.mode.PaymentMode.clear();
    }

    /** ctx.live()==FALSE forces the MOCK route (test rail, livemode=false). */
    private static CallContext testMode(String tenant) {
        return new CallContext(tenant, ScreeningMode.INTERACTIVE, Boolean.FALSE);
    }

    /** ctx.live()==TRUE forces the LIVE route (real delegate). */
    private static CallContext liveMode(String tenant) {
        return new CallContext(tenant, ScreeningMode.INTERACTIVE, Boolean.TRUE);
    }

    private static PaymentRequest req() {
        return new PaymentRequest(5000, "USD", "cust_1", "card", "4111111111111111",
                null, "desc", "automatic", "idem-1", Map.of());
    }

    private static PaymentResponse resp(String id, Instant createdAt) {
        return new PaymentResponse(id, PaymentResponse.STATUS_SUCCEEDED, 5000, "USD", "automatic",
                "cust_1", "mock", "txn_1", null, null, createdAt, Map.of());
    }

    private static RefundResponse refundResp(String id, Instant createdAt) {
        return new RefundResponse(id, "pay_test_1", RefundResponse.STATUS_SUCCEEDED, 2500, "USD",
                null, "mock", "rc_0", null, null, createdAt);
    }

    // ---- ★ CORE: a frozen clock re-stamps the mock create's createdAt ----

    @Test
    void mockCreate_withClockFrozen_recordsFrozenCreatedAt() {
        when(clockRepo.findByTenantId(TENANT_A)).thenReturn(Optional.of(FIXED));
        when(mockDelegate.createPayment(any())).thenReturn(resp("pay_test_1", MOCK_BUILT));

        PaymentResponse out = gateway.createPayment(req(), testMode(TENANT_A));

        // The returned response is re-stamped...
        assertThat(out.createdAt()).isEqualTo(FIXED);
        // ...and the SAME re-stamped response flows into the GAP-076 projection (so created_at + list
        // ordering are the frozen instant), NOT the mock's own EPOCH stamp.
        ArgumentCaptor<PaymentResponse> cap = ArgumentCaptor.forClass(PaymentResponse.class);
        verify(projection).record(cap.capture(), eq(TENANT_A), eq(false));
        assertThat(cap.getValue().createdAt()).isEqualTo(FIXED);
        // GAP-078 SHOULD_FIX: the MOCK STORE is also re-stamped with the SAME frozen instant, so a later
        // single-retrieve GET /v1/payments/{id} agrees with this response + the list (no real-time split).
        verify(mockDelegate).restampCreatedAt("pay_test_1", FIXED);
    }

    @Test
    void mockRefund_withClockFrozen_recordsFrozenCreatedAt() {
        when(clockRepo.findByTenantId(TENANT_A)).thenReturn(Optional.of(FIXED));
        when(mockDelegate.createRefund(any())).thenReturn(refundResp("re_test_1", MOCK_BUILT));

        RefundResponse out = gateway.createRefund(new RefundRequest("pay_test_1", 2500L, "USD", null, "k"));

        assertThat(out.createdAt()).isEqualTo(FIXED);
        ArgumentCaptor<RefundResponse> cap = ArgumentCaptor.forClass(RefundResponse.class);
        verify(projection).recordRefund(cap.capture(), eq(TENANT_A), eq(false));
        assertThat(cap.getValue().createdAt()).isEqualTo(FIXED);
        // GAP-078 SHOULD_FIX: the MOCK STORE refund is also re-stamped, so GET /v1/refunds/{id} agrees.
        verify(mockDelegate).restampCreatedAt("re_test_1", FIXED);
    }

    @Test
    void mockCreate_withNoClockSet_usesRealTime_notFrozen() {
        when(clockRepo.findByTenantId(TENANT_A)).thenReturn(Optional.empty()); // no clock -> real time
        Instant before = Instant.now();
        when(mockDelegate.createPayment(any())).thenReturn(resp("pay_test_1", MOCK_BUILT));

        gateway.createPayment(req(), testMode(TENANT_A));

        ArgumentCaptor<PaymentResponse> cap = ArgumentCaptor.forClass(PaymentResponse.class);
        verify(projection).record(cap.capture(), eq(TENANT_A), eq(false));
        // Re-stamped to real "now" (>= before, != the mock's EPOCH, != FIXED).
        assertThat(cap.getValue().createdAt()).isAfterOrEqualTo(before);
        assertThat(cap.getValue().createdAt()).isNotEqualTo(MOCK_BUILT);
        assertThat(cap.getValue().createdAt()).isNotEqualTo(FIXED);
    }

    // ---- ★ LIVE ISOLATION: a frozen tenant clock can NOT touch a live charge's timestamp ----

    @Test
    void liveCharge_withTenantClockFrozen_keepsRealDelegateCreatedAt_clockNeverConsulted() {
        // Tenant-A HAS a frozen clock set...
        when(clockRepo.findByTenantId(TENANT_A)).thenReturn(Optional.of(FIXED));
        // ...but the LIVE delegate stamps its OWN time and the gateway must not re-stamp it.
        when(delegate.createPayment(any())).thenReturn(resp("live_pay_1", LIVE_BUILT));

        PaymentResponse out = gateway.createPayment(req(), liveMode(TENANT_A));

        // The live charge keeps the real delegate timestamp — the frozen clock did NOT leak to it.
        assertThat(out.createdAt()).isEqualTo(LIVE_BUILT);
        ArgumentCaptor<PaymentResponse> cap = ArgumentCaptor.forClass(PaymentResponse.class);
        verify(projection).record(cap.capture(), eq(TENANT_A), eq(true)); // livemode=true on the live branch
        assertThat(cap.getValue().createdAt()).isEqualTo(LIVE_BUILT);
        assertThat(cap.getValue().createdAt()).isNotEqualTo(FIXED);
        // PROOF the clock is consulted ONLY on the mock rail: the live path never read the clock repo.
        verify(clockRepo, never()).findByTenantId(any());
        // And the mock delegate was never touched (this is the real PSP path) — including the store re-stamp,
        // which is mock-rail only and can never reach a live artifact.
        verify(mockDelegate, never()).createPayment(any());
        verify(mockDelegate, never()).restampCreatedAt(any(), any());
    }

    // ---- ★ TENANT ISOLATION: tenant-A's clock does not bleed to tenant-B ----

    @Test
    void mockCreate_tenantBHasNoClock_usesRealTime_eventhoughTenantAFrozen() {
        // tenant-A is frozen, tenant-B is NOT.
        when(clockRepo.findByTenantId(TENANT_A)).thenReturn(Optional.of(FIXED));
        when(clockRepo.findByTenantId(TENANT_B)).thenReturn(Optional.empty());
        Instant before = Instant.now();
        when(mockDelegate.createPayment(any())).thenReturn(resp("pay_test_B", MOCK_BUILT));

        gateway.createPayment(req(), testMode(TENANT_B));

        ArgumentCaptor<PaymentResponse> cap = ArgumentCaptor.forClass(PaymentResponse.class);
        verify(projection).record(cap.capture(), eq(TENANT_B), eq(false));
        // tenant-B used real time, NOT tenant-A's frozen instant (no cross-tenant bleed).
        assertThat(cap.getValue().createdAt()).isNotEqualTo(FIXED);
        assertThat(cap.getValue().createdAt()).isAfterOrEqualTo(before);
        // Only tenant-B's clock was consulted; tenant-A's was never read for this create.
        verify(clockRepo).findByTenantId(TENANT_B);
        verify(clockRepo, never()).findByTenantId(TENANT_A);
    }
}
