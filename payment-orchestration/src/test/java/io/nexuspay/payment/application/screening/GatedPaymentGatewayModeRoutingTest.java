package io.nexuspay.payment.application.screening;

import io.nexuspay.common.mode.PaymentMode;
import io.nexuspay.payment.adapter.out.hyperswitch.HyperSwitchPaymentAdapter;
import io.nexuspay.payment.adapter.out.mock.MockPaymentGatewayPort;
import io.nexuspay.payment.application.webhook.MockWebhookSynthesizer;
import io.nexuspay.payment.application.webhook.WebhookMetadataService;
import io.nexuspay.payment.domain.CaptureRequest;
import io.nexuspay.payment.domain.ConfirmRequest;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.RefundResponse;
import io.nexuspay.payment.domain.VoidRequest;
import io.nexuspay.payment.domain.event.PaymentEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * INT-3 — the CENTRAL guarantee: a TEST key can NEVER reach {@link HyperSwitchPaymentAdapter}.
 *
 * <ul>
 *   <li><b>T1</b> a TEST-mode caller routes EVERY port op to the mock; HyperSwitch is never touched.</li>
 *   <li><b>T2</b> a LIVE-mode caller routes EVERY port op to HyperSwitch; the mock is never touched
 *       (the determinable-LIVE carve-out).</li>
 *   <li><b>T3</b> a system/consumer thread (mode UNSET, no servlet request) resolves to HyperSwitch;
 *       a servlet REQUEST thread with mode UNSET FAILS CLOSED to the mock.</li>
 *   <li><b>T9</b> a TEST-mode create does NOT call the fraud/sanctions gate (the mock skips the
 *       money-out control) while a LIVE-mode create still screens.</li>
 * </ul>
 *
 * <p>Each test fails if the routing is reverted: revert T1 → HyperSwitch is called → {@code
 * verifyNoInteractions} fails; revert the request/system split → T3 flips; revert the gate-skip → T9
 * fails.</p>
 */
class GatedPaymentGatewayModeRoutingTest {

    private HyperSwitchPaymentAdapter hyperSwitch;
    private MockPaymentGatewayPort mockDelegate;
    private PreAuthorizationGate gate;
    private CaptureHoldService holds;
    private ScreeningOriginService origins;
    private WebhookMetadataService webhookMetadata;
    private MockWebhookSynthesizer synthesizer;
    private GatedPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        hyperSwitch = mock(HyperSwitchPaymentAdapter.class);
        mockDelegate = mock(MockPaymentGatewayPort.class);
        gate = mock(PreAuthorizationGate.class);
        holds = mock(CaptureHoldService.class);
        origins = mock(ScreeningOriginService.class);
        webhookMetadata = mock(WebhookMetadataService.class);
        synthesizer = mock(MockWebhookSynthesizer.class);
        lenient().when(origins.find(any())).thenReturn(Optional.empty());
        gateway = new GatedPaymentGateway(hyperSwitch, mockDelegate, gate, holds, origins,
                webhookMetadata, synthesizer,
                mock(io.nexuspay.payment.application.service.projection.PaymentProjectionService.class),
                new io.nexuspay.payment.application.service.clock.TestClockService(
                        mock(io.nexuspay.payment.application.port.out.TestClockRepository.class)));
    }

    @AfterEach
    void tearDown() {
        // Never leak the request-scoped mode or a bound servlet request onto the next test's thread.
        PaymentMode.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    private static PaymentRequest createReq() {
        return new PaymentRequest(5000, "USD", "cust_1", "card", "4111111111111111",
                null, "desc", "automatic", "idem-1", Map.of());
    }

    private static PaymentResponse paymentResp(String id) {
        return new PaymentResponse(id, PaymentResponse.STATUS_SUCCEEDED, 5000, "USD", "automatic",
                "cust_1", "mock", "txn_1", null, null, Instant.EPOCH, Map.of());
    }

    private static RefundResponse refundResp(String id) {
        return new RefundResponse(id, "pay_test_1", RefundResponse.STATUS_SUCCEEDED, 5000, "USD",
                "req", "mock", "txn_1", null, null, Instant.EPOCH);
    }

    private void stubMockHappyPath() {
        lenient().when(mockDelegate.createPayment(any())).thenReturn(paymentResp("pay_test_1"));
        lenient().when(mockDelegate.confirmPayment(any(), any())).thenReturn(paymentResp("pay_test_1"));
        lenient().when(mockDelegate.capturePayment(any(), any())).thenReturn(paymentResp("pay_test_1"));
        lenient().when(mockDelegate.voidPayment(any(), any())).thenReturn(paymentResp("pay_test_1"));
        lenient().when(mockDelegate.getPayment(any())).thenReturn(paymentResp("pay_test_1"));
        lenient().when(mockDelegate.createRefund(any())).thenReturn(refundResp("re_test_1"));
        lenient().when(mockDelegate.getRefund(any())).thenReturn(refundResp("re_test_1"));
    }

    private void stubLiveHappyPath() {
        lenient().when(gate.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(new GateDecision(false,
                        io.nexuspay.fraud.domain.model.RiskDecision.ALLOW,
                        java.util.UUID.randomUUID(), false, false));
        lenient().when(hyperSwitch.createPayment(any())).thenReturn(paymentResp("pay_live_1"));
        lenient().when(hyperSwitch.getPayment(any())).thenReturn(paymentResp("pay_live_1"));
        lenient().when(hyperSwitch.confirmPayment(any(), any())).thenReturn(paymentResp("pay_live_1"));
        lenient().when(hyperSwitch.capturePayment(any(), any())).thenReturn(paymentResp("pay_live_1"));
        lenient().when(hyperSwitch.voidPayment(any(), any())).thenReturn(paymentResp("pay_live_1"));
        lenient().when(hyperSwitch.createRefund(any())).thenReturn(refundResp("ref_live_1"));
        lenient().when(hyperSwitch.getRefund(any())).thenReturn(refundResp("ref_live_1"));
    }

    private void exerciseAllOps() {
        gateway.createPayment(createReq(), CallContext.interactive("t1"));
        gateway.confirmPayment("pay_x", new ConfirmRequest("card", "4111111111111111", null, "k"));
        gateway.capturePayment("pay_x", new CaptureRequest(5000L, "k"));
        gateway.voidPayment("pay_x", new VoidRequest("reason", "k"));
        gateway.getPayment("pay_x");
        gateway.createRefund(new RefundRequest("pay_x", 5000, "USD", "req", "k"));
        gateway.getRefund("re_x");
    }

    // ---- T1: TEST key NEVER reaches HyperSwitch (the guarantee) ----

    @Test
    void testKey_routesEveryOpToMock_neverHyperSwitch() {
        PaymentMode.set(false); // sk_test_ key
        stubMockHappyPath();

        exerciseAllOps();

        verify(mockDelegate).createPayment(any());
        verify(mockDelegate).confirmPayment(any(), any());
        verify(mockDelegate).capturePayment(any(), any());
        verify(mockDelegate).voidPayment(any(), any());
        verify(mockDelegate).getPayment(any());
        verify(mockDelegate).createRefund(any());
        verify(mockDelegate).getRefund(any());
        // The CENTRAL guarantee — a test key NEVER touches the real adapter.
        verifyNoInteractions(hyperSwitch);
    }

    // ---- T2: LIVE key DOES reach HyperSwitch (determinable-LIVE carve-out) ----

    @Test
    void liveKey_routesEveryOpToHyperSwitch_neverMock() {
        PaymentMode.set(true); // sk_live_ key
        stubLiveHappyPath();

        exerciseAllOps();

        verify(hyperSwitch).createPayment(any());
        verify(hyperSwitch).confirmPayment(any(), any());
        verify(hyperSwitch).capturePayment(any(), any());
        verify(hyperSwitch).voidPayment(any(), any());
        // getPayment is called twice on the live path (confirm loads the intent + the explicit get).
        verify(hyperSwitch, org.mockito.Mockito.atLeastOnce()).getPayment(any());
        verify(hyperSwitch).createRefund(any());
        verify(hyperSwitch).getRefund(any());
        verifyNoInteractions(mockDelegate);
    }

    // ---- T3: system/consumer thread (unset) -> LIVE; request thread (unset) -> mock (fail-closed) ----

    @Test
    void unsetMode_systemThread_resolvesToHyperSwitch() {
        PaymentMode.clear();                          // no mode set
        RequestContextHolder.resetRequestAttributes(); // no servlet request bound -> system thread
        stubLiveHappyPath();

        gateway.createPayment(createReq(), CallContext.interactive("t1"));

        verify(hyperSwitch).createPayment(any());
        verifyNoInteractions(mockDelegate);
    }

    @Test
    void unsetMode_requestThread_failsClosedToMock() {
        PaymentMode.clear(); // no mode resolved on this request (unauthenticated/edge path)
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        stubMockHappyPath();

        gateway.createPayment(createReq(), CallContext.interactive("t1"));

        // Fail closed: a request that reached a payment op unset must NOT risk a real charge.
        verify(mockDelegate).createPayment(any());
        verifyNoInteractions(hyperSwitch);
    }

    // ---- DX-5a (MONEY-SAFETY): the DURABLE CallContext.live() routes a SYSTEM-thread charge ----
    //
    // The @Scheduled @SystemTransactional renewal/dunning jobs charge on a SYSTEM thread (no servlet
    // request, PaymentMode UNSET). Pre-DX-5a, routeToMock() resolved such a thread to LIVE, so a TEST
    // subscription's recurring charge hit the REAL PSP — a CHARTER violation (a test charge must NEVER
    // reach HyperSwitch). createPayment has no payment id yet, so the isTestModeId fail-safe cannot
    // help. Billing now threads the subscription's durable is_live into ctx.live(); the gateway
    // consults it FIRST. These tests FAIL on the pre-DX-5a code (ctx.live() was ignored at create).

    @Test
    void systemThread_ctxLiveFalse_createRoutesToMock_neverHyperSwitch() {
        PaymentMode.clear();                          // no request-scoped mode (system thread)
        RequestContextHolder.resetRequestAttributes(); // no servlet request bound -> system thread
        stubMockHappyPath();

        // A TEST subscription's renewal/dunning charge: durable mode = test (live=false).
        gateway.createPayment(createReq(), CallContext.serverRecurring("t1", false));

        // The CHARTER guarantee on the system thread: a declared-TEST charge routes to the mock.
        verify(mockDelegate).createPayment(any());
        verifyNoInteractions(hyperSwitch);
    }

    @Test
    void systemThread_ctxLiveTrue_createRoutesToHyperSwitch_neverMock() {
        PaymentMode.clear();
        RequestContextHolder.resetRequestAttributes(); // system thread
        stubLiveHappyPath();

        // A LIVE subscription's renewal/dunning charge: durable mode = live (live=true).
        gateway.createPayment(createReq(), CallContext.serverRecurring("t1", true));

        verify(hyperSwitch).createPayment(any());
        verifyNoInteractions(mockDelegate);
    }

    @Test
    void systemThread_ctxLiveNull_preservesInvariant_resolvesToHyperSwitch() {
        // Invariant preserved: an INDETERMINATE durable mode (live=null) on a system thread still
        // resolves to the real PSP via the unchanged heuristic — a system thread with no DECLARED mode
        // is real. (serverRecurring without the live overload defaults live=null.)
        PaymentMode.clear();
        RequestContextHolder.resetRequestAttributes(); // system thread
        stubLiveHappyPath();

        gateway.createPayment(createReq(), CallContext.serverRecurring("t1"));

        verify(hyperSwitch).createPayment(any());
        verifyNoInteractions(mockDelegate);
    }

    @Test
    void requestThread_liveKey_ctxLiveFalse_stillRoutesTestToMock() {
        // Defense-in-depth: even if the executing request thread is explicitly LIVE, a charge that
        // DECLARES a TEST durable mode (e.g. a LIVE-key operator manually paying a TEST subscription's
        // invoice) routes to the mock — ctx.live()==FALSE wins over the request mode.
        PaymentMode.set(true); // request thread, live key
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        stubMockHappyPath();

        gateway.createPayment(createReq(), CallContext.serverRecurring("t1", false));

        verify(mockDelegate).createPayment(any());
        verifyNoInteractions(hyperSwitch);
    }

    // ---- T9: no SEC regression — test create skips the gate; live create still screens ----

    @Test
    void testCreate_skipsFraudSanctionsGate() {
        PaymentMode.set(false);
        stubMockHappyPath();

        gateway.createPayment(createReq(), CallContext.interactive("t1"));

        // The mock moves no real money + hits no PSP, so the money-out gate is skipped in test mode.
        verifyNoInteractions(gate);
        verify(mockDelegate).createPayment(any());
    }

    @Test
    void liveCreate_stillScreensWithTheGate() {
        PaymentMode.set(true);
        stubLiveHappyPath();

        gateway.createPayment(createReq(), CallContext.interactive("t1"));

        verify(gate).evaluate(any(), any(), eq("t1"), any(), any()); // live path still screens
        verify(hyperSwitch).createPayment(any());
    }

    // ---- BLOCKER: DEFERRED refund of a TEST payment NEVER reaches HyperSwitch ----
    //
    // An above-threshold refund is executed later by the RefundReconciler (SYSTEM thread, mode UNSET) or
    // the approver (a console/OIDC actor whose mode is LIVE). On BOTH the originating test key's
    // PaymentMode is gone, so routeToMock() alone would send a pay_test_* refund to HyperSwitch. The
    // id-prefix fail-safe must route a pay_test_*/re_test_* op to the mock regardless of thread mode.

    @Test
    void deferredRefund_systemThread_testPaymentId_routesToMock_neverHyperSwitch() {
        // Reconciler context: no servlet request bound, no mode set -> routeToMock()==false (system=LIVE).
        PaymentMode.clear();
        RequestContextHolder.resetRequestAttributes();
        stubMockHappyPath();
        stubLiveHappyPath();

        // The refund targets a mock-minted (pay_test_*) payment id.
        gateway.createRefund(new RefundRequest("pay_test_abc", 60000, "USD", "approved", "refund-approval-1"));

        verify(mockDelegate).createRefund(any());
        verify(synthesizer).onRefundTerminal(any(), any(), eq(PaymentEvent.REFUND_COMPLETED));
        verifyNoInteractions(hyperSwitch);
    }

    @Test
    void deferredRefund_liveApproverThread_testPaymentId_routesToMock_neverHyperSwitch() {
        // Approver context: a console/OIDC actor resolves LIVE on a bound request thread.
        PaymentMode.set(true);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        stubMockHappyPath();
        stubLiveHappyPath();

        gateway.createRefund(new RefundRequest("pay_test_xyz", 60000, "USD", "approved", "refund-approval-2"));

        // Even though the executing thread is explicitly LIVE, a pay_test_* target routes to the mock.
        verify(mockDelegate).createRefund(any());
        verifyNoInteractions(hyperSwitch);
    }

    @Test
    void deferredGetRefund_testRefundId_routesToMock_neverHyperSwitch() {
        PaymentMode.clear();
        RequestContextHolder.resetRequestAttributes(); // system thread
        stubMockHappyPath();
        stubLiveHappyPath();

        gateway.getRefund("re_test_999");

        verify(mockDelegate).getRefund(eq("re_test_999"));
        verifyNoInteractions(hyperSwitch);
    }

    @Test
    void liveRefund_realPaymentId_systemThread_stillReachesHyperSwitch() {
        // Guard the carve-out: a genuine LIVE refund (real, non-test payment id) on a system thread must
        // STILL reach HyperSwitch — the fail-safe only diverts test-prefixed ids, never the reverse.
        PaymentMode.clear();
        RequestContextHolder.resetRequestAttributes();
        stubLiveHappyPath();

        gateway.createRefund(new RefundRequest("pay_live_real", 60000, "USD", "approved", "refund-approval-3"));

        verify(hyperSwitch).createRefund(any());
        verifyNoInteractions(mockDelegate);
    }

    // ---- SHOULD_FIX (L-044): the gateway -> synthesizer WIRING (invariant 5: test-mode webhooks MUST
    // fire) must be verified. Deleting the synthesizer call from any terminal mock branch must fail a test.

    @Test
    void testAutoCaptureCreate_synthesizesPaymentCaptured() {
        PaymentMode.set(false); // test mode
        // Auto-capture create settles immediately (succeeded = terminal money state).
        when(mockDelegate.createPayment(any())).thenReturn(paymentResp("pay_test_create"));

        gateway.createPayment(createReq(), CallContext.interactive("t1"));

        // Fails if the onTerminal(...) call is removed from doCreate's mock branch.
        verify(synthesizer).onTerminal(any(), eq("t1"), eq(PaymentEvent.PAYMENT_CAPTURED));
    }

    @Test
    void testManualCreate_nonTerminal_doesNotSynthesize() {
        PaymentMode.set(false); // test mode
        // A manual-capture intent authorizes only -> requires_capture (NOT a terminal money state).
        PaymentRequest manual = new PaymentRequest(5000, "USD", "cust_1", "card", "4111111111111111",
                null, "desc", "manual", "idem-manual", Map.of());
        PaymentResponse requiresCapture = new PaymentResponse("pay_test_manual",
                PaymentResponse.STATUS_REQUIRES_CAPTURE, 5000, "USD", "manual",
                "cust_1", "mock", "txn_1", null, null, Instant.EPOCH, Map.of());
        when(mockDelegate.createPayment(any())).thenReturn(requiresCapture);

        gateway.createPayment(manual, CallContext.interactive("t1"));

        // No terminal money state on a manual create -> NO synthesis (fails if the gateway synthesizes
        // unconditionally on every mock create).
        verifyNoInteractions(synthesizer);
    }

    @Test
    void testCapture_synthesizesPaymentCaptured() {
        PaymentMode.set(false); // test mode
        when(mockDelegate.capturePayment(any(), any())).thenReturn(paymentResp("pay_test_cap"));
        when(origins.find(any())).thenReturn(
                Optional.of(new ScreeningOriginService.Origin("t1", ScreeningMode.INTERACTIVE)));

        gateway.capturePayment("pay_test_cap", new CaptureRequest(5000L, "k"));

        // Fails if the onTerminal(...) call is removed from capturePayment's mock branch.
        verify(synthesizer).onTerminal(any(), eq("t1"), eq(PaymentEvent.PAYMENT_CAPTURED));
    }

    @Test
    void testRefund_synthesizesRefundCompleted_withResolvedTenant() {
        PaymentMode.set(false); // test mode
        when(mockDelegate.createRefund(any())).thenReturn(refundResp("re_test_r"));
        when(origins.find(any())).thenReturn(
                Optional.of(new ScreeningOriginService.Origin("t1", ScreeningMode.INTERACTIVE)));

        gateway.createRefund(new RefundRequest("pay_test_1", 5000, "USD", "req", "k"));

        // Fails if the onRefundTerminal(...) call is removed from createRefund's mock branch.
        verify(synthesizer).onRefundTerminal(any(), eq("t1"), eq(PaymentEvent.REFUND_COMPLETED));
    }

    // ---- TEST-1: a forced FAILED mock outcome wires the FAILURE synthesizer (mock-only path) ----

    @Test
    void testForcedFailedCreate_synthesizesPaymentFailed_notCaptured() {
        PaymentMode.set(false); // test mode
        PaymentResponse failed = new PaymentResponse("pay_test_fail", PaymentResponse.STATUS_FAILED,
                5000, "USD", "automatic", "cust_1", "mock", "txn_1",
                "card_declined", "Your card was declined.", Instant.EPOCH, Map.of());
        when(mockDelegate.createPayment(any())).thenReturn(failed);

        gateway.createPayment(createReq(), CallContext.interactive("t1"));

        // The forced decline emits payment.failed and NEVER the success (captured) event.
        verify(synthesizer).onTerminalFailure(any(), eq("t1"), eq(PaymentEvent.PAYMENT_FAILED));
        org.mockito.Mockito.verify(synthesizer, org.mockito.Mockito.never())
                .onTerminal(any(), any(), eq(PaymentEvent.PAYMENT_CAPTURED));
        // The forced failure stays inside the mock — HyperSwitch is never touched.
        verifyNoInteractions(hyperSwitch);
    }

    @Test
    void testForcedFailedRefund_synthesizesRefundFailed() {
        PaymentMode.set(false); // test mode
        RefundResponse failed = new RefundResponse("re_test_fail", "pay_test_1",
                RefundResponse.STATUS_FAILED, 1066, "USD", "req", "mock", "txn_1",
                "refund_failed", "The refund failed at the processor.", Instant.EPOCH);
        when(mockDelegate.createRefund(any())).thenReturn(failed);
        when(origins.find(any())).thenReturn(
                Optional.of(new ScreeningOriginService.Origin("t1", ScreeningMode.INTERACTIVE)));

        gateway.createRefund(new RefundRequest("pay_test_1", 1066, "USD", "req", "k"));

        // A failed mock refund emits payment.refund.failed, NOT the completed event.
        verify(synthesizer).onRefundFailed(any(), eq("t1"), eq(PaymentEvent.REFUND_FAILED));
        org.mockito.Mockito.verify(synthesizer, org.mockito.Mockito.never())
                .onRefundTerminal(any(), any(), eq(PaymentEvent.REFUND_COMPLETED));
    }
}
