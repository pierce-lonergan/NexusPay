package io.nexuspay.payment.application.screening;

import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.fraud.domain.model.RiskDecision;
import io.nexuspay.payment.adapter.out.hyperswitch.HyperSwitchPaymentAdapter;
import io.nexuspay.payment.adapter.out.mock.MockPaymentGatewayPort;
import io.nexuspay.payment.domain.CaptureRequest;
import io.nexuspay.payment.domain.ConfirmRequest;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.VoidRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the B-024 {@link GatedPaymentGateway} @Primary decorator: that it screens
 * at the port boundary, classifies the flow from metadata, holds capture on REVIEW, enforces
 * the hold at capture, re-screens confirm-with-new-PM, and passes void/get/refund straight through.
 */
class GatedPaymentGatewayTest {

    private HyperSwitchPaymentAdapter delegate;
    private MockPaymentGatewayPort mockDelegate;
    private PreAuthorizationGate gate;
    private CaptureHoldService holds;
    private ScreeningOriginService origins;
    private io.nexuspay.payment.application.webhook.WebhookMetadataService webhookMetadata;
    private io.nexuspay.payment.application.webhook.MockWebhookSynthesizer mockSynthesizer;
    private GatedPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        delegate = mock(HyperSwitchPaymentAdapter.class);
        mockDelegate = mock(MockPaymentGatewayPort.class);
        gate = mock(PreAuthorizationGate.class);
        holds = mock(CaptureHoldService.class);
        origins = mock(ScreeningOriginService.class);
        webhookMetadata = mock(io.nexuspay.payment.application.webhook.WebhookMetadataService.class);
        mockSynthesizer = mock(io.nexuspay.payment.application.webhook.MockWebhookSynthesizer.class);
        when(origins.find(any())).thenReturn(Optional.empty()); // default: no origin → strict fallback
        // INT-3: these tests assert the LIVE/screening path. They run on a plain unit thread (no servlet
        // request, PaymentMode unset) so routeToMock() resolves to the real delegate; the mock
        // collaborators are supplied but never invoked here. PaymentMode.clear() in tearDown guards against
        // any leakage from other tests on the same (reused) thread.
        gateway = new GatedPaymentGateway(delegate, mockDelegate, gate, holds, origins, webhookMetadata,
                mockSynthesizer,
                mock(io.nexuspay.payment.application.service.projection.PaymentProjectionService.class));
    }

    @org.junit.jupiter.api.AfterEach
    void clearMode() {
        io.nexuspay.common.mode.PaymentMode.clear();
    }

    private static PaymentRequest req(Map<String, Object> metadata) {
        return new PaymentRequest(5000, "USD", "cust_1", "card", "4111111111111111",
                null, "desc", "automatic", "idem-1", metadata);
    }

    private static PaymentResponse resp(String id, String captureMethod) {
        return new PaymentResponse(id, "requires_capture", 5000, "USD", captureMethod,
                "cust_1", "stripe", "txn_1", null, null, Instant.EPOCH, Map.of());
    }

    private static GateDecision allow() {
        return new GateDecision(false, RiskDecision.ALLOW, UUID.randomUUID(), false, false);
    }

    private static GateDecision review() {
        return new GateDecision(true, RiskDecision.REVIEW, UUID.randomUUID(), false, false);
    }

    @Test
    void createPayment_allow_passesThroughUnchanged_noHold() {
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(allow());
        when(delegate.createPayment(any())).thenReturn(resp("pay_1", "automatic"));

        gateway.createPayment(req(Map.of("tenant_id", "t1")));

        ArgumentCaptor<PaymentRequest> sent = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(delegate).createPayment(sent.capture());
        assertThat(sent.getValue().captureMethod()).isEqualTo("automatic"); // not forced to manual
        verify(holds, never()).hold(any(), any(), any());
    }

    @Test
    void createPayment_review_forcesManualCapture_andWritesHold() {
        // SHOULD_FIX B: the 1-arg path is the STRICT fallback (INTERACTIVE + null tenant); the
        // tenant_id in metadata is NOT honoured, so the hold is written with a null tenant. The
        // strict-INTERACTIVE REVIEW still forces manual capture + writes a hold (strictest behavior).
        GateDecision review = review();
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(review);
        when(delegate.createPayment(any())).thenReturn(resp("pay_42", "manual"));

        gateway.createPayment(req(Map.of("tenant_id", "t1")));

        ArgumentCaptor<PaymentRequest> sent = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(delegate).createPayment(sent.capture());
        assertThat(sent.getValue().captureMethod()).isEqualTo("manual");      // capture held
        verify(holds).hold(eq("pay_42"), eq((String) null), eq(review.fraudAssessmentId())); // null tenant — metadata ignored
    }

    @Test
    void createPayment_1Arg_softRailMetadata_isIGNORED_screensStrictInteractive() {
        // SHOULD_FIX B (security footgun killed): the transitional 1-arg path must NEVER let client
        // metadata grant a softer rail. A client claiming source=billing_subscription used to be
        // classified SERVER_RECURRING (dodging the INTERACTIVE capture-hold) — now the 1-arg path
        // delegates to CallContext.strictDefault, so the gate screens STRICT INTERACTIVE with a null
        // tenant regardless of the metadata.
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(allow());
        when(delegate.createPayment(any())).thenReturn(resp("pay_1", "automatic"));

        gateway.createPayment(req(Map.of("tenant_id", "t1", "source", "billing_subscription")));

        ArgumentCaptor<ScreeningMode> mode = ArgumentCaptor.forClass(ScreeningMode.class);
        ArgumentCaptor<String> tenant = ArgumentCaptor.forClass(String.class);
        verify(gate).evaluate(any(), any(), tenant.capture(), any(), mode.capture());
        assertThat(mode.getValue()).isEqualTo(ScreeningMode.INTERACTIVE); // NOT SERVER_RECURRING
        assertThat(tenant.getValue()).isNull();                          // NOT "t1" from metadata
    }

    @Test
    void createPayment_1Arg_softRailReview_isHeldBecauseTreatedAsInteractive() {
        // SHOULD_FIX B follow-through: because the 1-arg path is strict INTERACTIVE, a fraud REVIEW
        // now HOLDS capture (forces manual) even when the client metadata claimed a server rail —
        // the metadata-claimed "server rail captures clean" loophole is gone. (The real soft rail is
        // still reachable, but ONLY via the trusted CallContext.serverRecurring(...) factory.)
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(review());
        when(delegate.createPayment(any())).thenReturn(resp("pay_b", "manual"));

        gateway.createPayment(req(Map.of("tenant_id", "t1", "source", "billing_subscription")));

        ArgumentCaptor<PaymentRequest> sent = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(delegate).createPayment(sent.capture());
        assertThat(sent.getValue().captureMethod()).isEqualTo("manual"); // forced manual — NOT clean
        verify(holds).hold(eq("pay_b"), eq((String) null), any());       // held, null tenant
    }

    @Test
    void createPayment_interactiveByDefault() {
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(allow());
        when(delegate.createPayment(any())).thenReturn(resp("pay_1", "automatic"));

        gateway.createPayment(req(Map.of("tenant_id", "t1")));

        ArgumentCaptor<ScreeningMode> mode = ArgumentCaptor.forClass(ScreeningMode.class);
        verify(gate).evaluate(any(), any(), any(), any(), mode.capture());
        assertThat(mode.getValue()).isEqualTo(ScreeningMode.INTERACTIVE);
    }

    @Test
    void capturePayment_held_isRefused_delegateNotCalled() {
        when(holds.isHeld("pay_held")).thenReturn(true);

        assertThatThrownBy(() -> gateway.capturePayment("pay_held", new CaptureRequest(5000L, "k")))
                .isInstanceOfSatisfying(PaymentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("capture_hold_review"));

        verify(delegate, never()).capturePayment(any(), any());
    }

    @Test
    void capturePayment_notHeld_passesThrough() {
        when(holds.isHeld("pay_ok")).thenReturn(false);
        when(delegate.capturePayment(any(), any())).thenReturn(resp("pay_ok", "manual"));

        gateway.capturePayment("pay_ok", new CaptureRequest(5000L, "k"));

        verify(delegate).capturePayment(eq("pay_ok"), any());
    }

    @Test
    void confirmPayment_withNewPaymentMethod_reScreensOnTheNewInstrument() {
        when(delegate.getPayment("pay_1")).thenReturn(resp("pay_1", "automatic"));
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(allow());
        when(delegate.confirmPayment(any(), any())).thenReturn(resp("pay_1", "automatic"));

        gateway.confirmPayment("pay_1", new ConfirmRequest("card", "5555555555554444", null, "k"));

        verify(delegate).getPayment("pay_1");
        verify(gate).evaluate(any(), any(), any(), any(), any()); // re-screened
        verify(delegate).confirmPayment(eq("pay_1"), any());
    }

    @Test
    void confirmPayment_alwaysScreens_evenWithoutNewPaymentMethod() {
        // B2: sanctions must run on every confirm, so the gate is always consulted.
        when(delegate.getPayment("pay_1")).thenReturn(resp("pay_1", "automatic"));
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(allow());
        when(delegate.confirmPayment(any(), any())).thenReturn(resp("pay_1", "automatic"));

        gateway.confirmPayment("pay_1", new ConfirmRequest(null, null, null, "k"));

        verify(delegate).getPayment("pay_1");
        verify(gate).evaluate(any(), any(), any(), any(), any()); // screened even with no new PM
        verify(delegate).confirmPayment(eq("pay_1"), any());
    }

    @Test
    void confirmPayment_interactiveReview_onAutoCaptureIntent_isRejected_beforeConfirm() {
        // B1: cannot retroactively hold capture at confirm on an auto-capture intent → reject.
        when(delegate.getPayment("pay_auto")).thenReturn(resp("pay_auto", "automatic"));
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(review());

        assertThatThrownBy(() -> gateway.confirmPayment("pay_auto", new ConfirmRequest("card", "5555555555554444", null, "k")))
                .isInstanceOfSatisfying(PaymentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("fraud_review_hold"));

        verify(holds).hold(eq("pay_auto"), any(), any());
        verify(delegate, never()).confirmPayment(any(), any()); // PSP never confirmed/captured
    }

    @Test
    void confirmPayment_interactiveReview_onManualIntent_proceeds_withHold() {
        // A manual-capture intent confirms safely: authorizes only, capture stays HELD.
        when(delegate.getPayment("pay_manual")).thenReturn(resp("pay_manual", "manual"));
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(review());
        when(delegate.confirmPayment(any(), any())).thenReturn(resp("pay_manual", "manual"));

        gateway.confirmPayment("pay_manual", new ConfirmRequest("card", "5555555555554444", null, "k"));

        verify(holds).hold(eq("pay_manual"), any(), any());
        verify(delegate).confirmPayment(eq("pay_manual"), any());
    }

    @Test
    void confirmPayment_getPaymentError_remappedToPaymentException() {
        // M3: getPayment is circuit-broken with no fallback — a raw runtime error must surface
        // as a PaymentException, not an unmapped 500.
        when(delegate.getPayment("pay_x")).thenThrow(new IllegalStateException("circuit open"));

        assertThatThrownBy(() -> gateway.confirmPayment("pay_x", new ConfirmRequest("card", "4111111111111111", null, "k")))
                .isInstanceOfSatisfying(PaymentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("payment_screen_unavailable"));
    }

    @Test
    void voidAndGet_passStraightThrough_gateNeverInvoked() {
        when(delegate.voidPayment(any(), any())).thenReturn(resp("pay_1", "automatic"));
        when(delegate.getPayment("pay_1")).thenReturn(resp("pay_1", "automatic"));

        gateway.voidPayment("pay_1", new VoidRequest("reason", "k"));
        gateway.getPayment("pay_1");

        verifyNoInteractions(gate);
        verifyNoInteractions(holds);
    }

    // ---- B-029: MODE + TENANT authority comes from the trusted CallContext, NOT client metadata ----

    @Test
    void create_forgedSoftRailAndTenantInMetadata_interactiveCtx_bothIgnored() {
        // ATTACK (bypass-A + bypass-B in one): client metadata claims the soft SERVER_RECURRING rail
        // (source=billing_subscription) AND a foreign tenant (tenant_id=attacker). A trusted
        // INTERACTIVE ctx for tenant "trusted-T" is supplied. The gate MUST screen as INTERACTIVE for
        // "trusted-T" — never SERVER_RECURRING, never "attacker".
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(allow());
        when(delegate.createPayment(any())).thenReturn(resp("pay_1", "automatic"));

        gateway.createPayment(
                req(Map.of("source", "billing_subscription", "workflow", "x", "tenant_id", "attacker")),
                CallContext.interactive("trusted-T"));

        ArgumentCaptor<ScreeningMode> mode = ArgumentCaptor.forClass(ScreeningMode.class);
        verify(gate).evaluate(any(), any(), eq("trusted-T"), any(), mode.capture());
        assertThat(mode.getValue()).isEqualTo(ScreeningMode.INTERACTIVE); // NOT SERVER_RECURRING
    }

    @Test
    void create_forgedTenantOnly_isIgnored_trustedTenantWins() {
        // ATTACK bypass-B: only the tenant is forged. The trusted ctx tenant must win so fraud
        // velocity cannot be fragmented across fabricated tenant ids.
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(allow());
        when(delegate.createPayment(any())).thenReturn(resp("pay_1", "automatic"));

        gateway.createPayment(req(Map.of("tenant_id", "attacker")), CallContext.interactive("trusted-T"));

        verify(gate).evaluate(any(), any(), eq("trusted-T"), any(), any());
    }

    @Test
    void create_softRailGrantedOnlyViaTrustedChannel_andServerReviewCapturesClean() {
        // The soft rail is reachable ONLY through the typed factory (a trusted server channel), not
        // client metadata. A fraud REVIEW on this rail captures clean + no hold (M1 preserved).
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(review());
        when(delegate.createPayment(any())).thenReturn(resp("pay_sr", "automatic"));

        gateway.createPayment(req(Map.of()), CallContext.serverRecurring("T"));

        ArgumentCaptor<ScreeningMode> mode = ArgumentCaptor.forClass(ScreeningMode.class);
        ArgumentCaptor<PaymentRequest> sent = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(gate).evaluate(any(), any(), eq("T"), any(), mode.capture());
        assertThat(mode.getValue()).isEqualTo(ScreeningMode.SERVER_RECURRING);
        verify(delegate).createPayment(sent.capture());
        assertThat(sent.getValue().captureMethod()).isEqualTo("automatic"); // NOT held (M1)
        verify(holds, never()).hold(any(), any(), any());
    }

    @Test
    void create_noCtx_legacy1ArgPath_defaultsToStrictInteractive_nullTenant_andReviewHolds() {
        // SHOULD_FIX B: the transitional 1-arg path → strict INTERACTIVE + NULL tenant (the
        // metadata tenant_id is NOT honoured); a fraud REVIEW holds (the strictest behavior) and a
        // hold row is written with a null tenant.
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(review());
        when(delegate.createPayment(any())).thenReturn(resp("pay_x", "manual"));

        gateway.createPayment(req(Map.of("tenant_id", "t1")));

        ArgumentCaptor<ScreeningMode> mode = ArgumentCaptor.forClass(ScreeningMode.class);
        ArgumentCaptor<String> tenant = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<PaymentRequest> sent = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(gate).evaluate(any(), any(), tenant.capture(), any(), mode.capture());
        assertThat(mode.getValue()).isEqualTo(ScreeningMode.INTERACTIVE);
        assertThat(tenant.getValue()).isNull();                       // metadata tenant_id ignored
        verify(delegate).createPayment(sent.capture());
        assertThat(sent.getValue().captureMethod()).isEqualTo("manual");
        verify(holds).hold(eq("pay_x"), eq((String) null), any());    // null tenant
    }

    @Test
    void create_ctxPath_stripsClientAuthorityMarkers_fromMetadataSentToPsp() {
        // Defense-in-depth: source/workflow/tenant_id must be removed from the metadata forwarded to
        // the PSP (and thus never persisted as authority). ip_country_trusted (B-025) is untouched.
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(allow());
        when(delegate.createPayment(any())).thenReturn(resp("pay_1", "automatic"));

        gateway.createPayment(
                req(new java.util.HashMap<>(Map.of(
                        "source", "billing_subscription",
                        "workflow", "x",
                        "tenant_id", "attacker",
                        "ip_country_trusted", "US",
                        "invoice_id", "inv_9"))),
                CallContext.interactive("trusted-T"));

        ArgumentCaptor<PaymentRequest> sent = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(delegate).createPayment(sent.capture());
        Map<String, Object> forwarded = sent.getValue().metadata();
        assertThat(forwarded).doesNotContainKeys("source", "workflow", "tenant_id");
        assertThat(forwarded).containsEntry("ip_country_trusted", "US"); // B-025 key preserved
        assertThat(forwarded).containsEntry("invoice_id", "inv_9");      // benign keys preserved
    }

    @Test
    void create_ctxPath_recordsTrustedOriginForConfirm() {
        // The trusted (tenant, mode) is persisted to the origin store keyed by the gateway payment id.
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(allow());
        when(delegate.createPayment(any())).thenReturn(resp("pay_origin", "automatic"));

        gateway.createPayment(req(Map.of()), CallContext.serverRecurring("T"));

        ArgumentCaptor<CallContext> ctx = ArgumentCaptor.forClass(CallContext.class);
        verify(origins).record(eq("pay_origin"), ctx.capture());
        assertThat(ctx.getValue().tenantId()).isEqualTo("T");
        assertThat(ctx.getValue().mode()).isEqualTo(ScreeningMode.SERVER_RECURRING);
    }

    @Test
    void confirm_authorityComesFromOriginStore_notTamperedMetadata() {
        // ATTACK at confirm: the persisted intent metadata is tampered to claim INTERACTIVE-attacker
        // tenant + an interactive source, but the SERVER-OWNED origin store says SERVER_RECURRING/"T".
        // The gate MUST screen with the origin store's (tenant=T, mode=SERVER_RECURRING).
        when(origins.find("pay_c")).thenReturn(
                Optional.of(new ScreeningOriginService.Origin("T", ScreeningMode.SERVER_RECURRING)));
        when(delegate.getPayment("pay_c")).thenReturn(new PaymentResponse(
                "pay_c", "requires_confirmation", 5000, "USD", "manual", "cust_1", "stripe", "txn_1",
                null, null, Instant.EPOCH, Map.of("tenant_id", "attacker", "source", "interactive_attacker")));
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(allow());
        when(delegate.confirmPayment(any(), any())).thenReturn(resp("pay_c", "manual"));

        gateway.confirmPayment("pay_c", new ConfirmRequest(null, null, null, "k"));

        ArgumentCaptor<ScreeningMode> mode = ArgumentCaptor.forClass(ScreeningMode.class);
        verify(gate).evaluate(any(), any(), eq("T"), any(), mode.capture());
        assertThat(mode.getValue()).isEqualTo(ScreeningMode.SERVER_RECURRING); // from origin, NOT metadata
    }

    @Test
    void confirm_noOriginRow_fallsBackToStrictInteractive_notMetadata() {
        // Legacy intent: no origin row. Confirm must fall back to strict INTERACTIVE + null tenant,
        // NOT re-derive a soft rail from the metadata's source=billing marker.
        when(origins.find("pay_legacy")).thenReturn(Optional.empty());
        when(delegate.getPayment("pay_legacy")).thenReturn(new PaymentResponse(
                "pay_legacy", "requires_confirmation", 5000, "USD", "manual", "cust_1", "stripe",
                "txn_1", null, null, Instant.EPOCH, Map.of("source", "billing_subscription", "tenant_id", "t1")));
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(allow());
        when(delegate.confirmPayment(any(), any())).thenReturn(resp("pay_legacy", "manual"));

        gateway.confirmPayment("pay_legacy", new ConfirmRequest(null, null, null, "k"));

        ArgumentCaptor<ScreeningMode> mode = ArgumentCaptor.forClass(ScreeningMode.class);
        ArgumentCaptor<String> tenant = ArgumentCaptor.forClass(String.class);
        verify(gate).evaluate(any(), any(), tenant.capture(), any(), mode.capture());
        assertThat(mode.getValue()).isEqualTo(ScreeningMode.INTERACTIVE); // strict fallback, NOT from metadata
        assertThat(tenant.getValue()).isNull();                          // no tenant from a tampered blob
    }

    // ---- TEST-6 (A3/A4): the new non-failure outcomes synthesize NO terminal webhook (through the GATE) ----
    //
    // The blueprint names "processing returns processing with no terminal webhook" (A4) and "NO terminal
    // webhook is synthesized for requires_action" (A3) as explicit deliverables. The mock NEVER synthesizes
    // webhooks — the GATE does (doCreate fires the synthesizer ONLY on response.isSuccessful() ->
    // PaymentCaptured, else response.isFailed() -> payment.failed; REQUIRES_ACTION / PROCESSING /
    // REQUIRES_CAPTURE fall through both branches). So this is the ONLY place that can assert the no-webhook
    // property. A future change to the synthesis branch (e.g. an `else if requiresCapture()` arm) would
    // otherwise silently violate A3/A4 with green tests.
    //
    // These cases drive the MOCK path by supplying ctx.live()==FALSE (routeToMock(false) -> true), which is
    // the test-mode routing an sk_test_ key takes. mockDelegate is a Mockito mock here, so we stub the mock
    // create to return each TEST-6 status and verify the synthesizer is / is not invoked accordingly.

    private static PaymentResponse mockResp(String status, String captureMethod) {
        return new PaymentResponse("pay_test_x", status, 5000, "USD", captureMethod,
                "cust_1", "mock", "txn_test_1", null, null, Instant.EPOCH, Map.of());
    }

    private static CallContext testMode() {
        // ctx.live()==FALSE forces the mock route in doCreate (DX-5a routeToMock(ctxLive)).
        return new CallContext("T", ScreeningMode.INTERACTIVE, Boolean.FALSE);
    }

    @Test
    void testMode_processing_synthesizesNoTerminalWebhook() {
        // A4: a forced `processing` create is non-terminal — the gate must NOT synthesize any webhook.
        when(mockDelegate.createPayment(any())).thenReturn(mockResp(PaymentResponse.STATUS_PROCESSING, "automatic"));

        PaymentResponse r = gateway.createPayment(req(Map.of()), testMode());

        assertThat(r.status()).isEqualTo(PaymentResponse.STATUS_PROCESSING);
        verify(mockDelegate).createPayment(any());     // routed to the mock (not the real delegate)
        verifyNoInteractions(delegate);                // the real PSP is never touched in test mode
        verifyNoInteractions(mockSynthesizer);         // NO terminal webhook for a non-terminal state (A4)
    }

    @Test
    void testMode_requiresAction_synthesizesNoTerminalWebhook() {
        // A3: a forced `requires_action` (3DS/SCA) create is non-terminal — no webhook is synthesized.
        when(mockDelegate.createPayment(any()))
                .thenReturn(mockResp(PaymentResponse.STATUS_REQUIRES_ACTION, "automatic"));

        PaymentResponse r = gateway.createPayment(req(Map.of()), testMode());

        assertThat(r.status()).isEqualTo(PaymentResponse.STATUS_REQUIRES_ACTION);
        verify(mockDelegate).createPayment(any());
        verifyNoInteractions(delegate);
        verifyNoInteractions(mockSynthesizer);         // NO terminal webhook for a non-terminal state (A3)
    }

    @Test
    void testMode_fraudHold_requiresCapture_synthesizesNoTerminalWebhook() {
        // A5 (option b): a forced `fraud_hold` -> requires_capture (manual-capture shape) is NOT terminal at
        // create — the gate synthesizes nothing here (capture later fires PaymentCaptured via the capture path).
        when(mockDelegate.createPayment(any()))
                .thenReturn(mockResp(PaymentResponse.STATUS_REQUIRES_CAPTURE, "manual"));

        PaymentResponse r = gateway.createPayment(req(Map.of()), testMode());

        assertThat(r.status()).isEqualTo(PaymentResponse.STATUS_REQUIRES_CAPTURE);
        verify(mockDelegate).createPayment(any());
        verifyNoInteractions(delegate);
        verifyNoInteractions(mockSynthesizer);         // requires_capture is not terminal at create
    }

    @Test
    void testMode_success_synthesizesPaymentCaptured_positiveControl() {
        // POSITIVE CONTROL: an auto-capture success IS terminal -> the gate DOES synthesize PaymentCaptured.
        // This proves the no-webhook assertions above are meaningful (the synthesizer is wired + reachable).
        when(mockDelegate.createPayment(any())).thenReturn(mockResp(PaymentResponse.STATUS_SUCCEEDED, "automatic"));

        gateway.createPayment(req(Map.of()), testMode());

        verify(mockSynthesizer).onTerminal(any(), any(),
                eq(io.nexuspay.payment.domain.event.PaymentEvent.PAYMENT_CAPTURED));
        verify(mockSynthesizer, never()).onTerminalFailure(any(), any(), any());
    }

    @Test
    void testMode_decline_synthesizesPaymentFailed_positiveControl() {
        // POSITIVE CONTROL: a forced decline IS terminal (failed) -> the gate synthesizes payment.failed.
        when(mockDelegate.createPayment(any())).thenReturn(new PaymentResponse(
                "pay_test_x", PaymentResponse.STATUS_FAILED, 5000, "USD", "automatic", "cust_1",
                "mock", "txn_test_1", "card_declined", "Your card was declined.", Instant.EPOCH, Map.of()));

        gateway.createPayment(req(Map.of()), testMode());

        verify(mockSynthesizer).onTerminalFailure(any(), any(),
                eq(io.nexuspay.payment.domain.event.PaymentEvent.PAYMENT_FAILED));
        verify(mockSynthesizer, never()).onTerminal(any(), any(), any());
    }
}
