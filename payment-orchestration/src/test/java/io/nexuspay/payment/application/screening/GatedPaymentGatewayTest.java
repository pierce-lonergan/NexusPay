package io.nexuspay.payment.application.screening;

import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.fraud.domain.model.RiskDecision;
import io.nexuspay.payment.adapter.out.hyperswitch.HyperSwitchPaymentAdapter;
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
    private PreAuthorizationGate gate;
    private CaptureHoldService holds;
    private GatedPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        delegate = mock(HyperSwitchPaymentAdapter.class);
        gate = mock(PreAuthorizationGate.class);
        holds = mock(CaptureHoldService.class);
        gateway = new GatedPaymentGateway(delegate, gate, holds);
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
        GateDecision review = review();
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(review);
        when(delegate.createPayment(any())).thenReturn(resp("pay_42", "manual"));

        gateway.createPayment(req(Map.of("tenant_id", "t1")));

        ArgumentCaptor<PaymentRequest> sent = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(delegate).createPayment(sent.capture());
        assertThat(sent.getValue().captureMethod()).isEqualTo("manual");      // capture held
        verify(holds).hold("pay_42", "t1", review.fraudAssessmentId());        // linked hold written
    }

    @Test
    void createPayment_classifiesServerRecurring_fromBillingMetadata() {
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(allow());
        when(delegate.createPayment(any())).thenReturn(resp("pay_1", "automatic"));

        gateway.createPayment(req(Map.of("tenant_id", "t1", "source", "billing_subscription")));

        ArgumentCaptor<ScreeningMode> mode = ArgumentCaptor.forClass(ScreeningMode.class);
        verify(gate).evaluate(any(), any(), eq("t1"), any(), mode.capture());
        assertThat(mode.getValue()).isEqualTo(ScreeningMode.SERVER_RECURRING);
    }

    @Test
    void createPayment_serverRecurringReview_capturesNotHeld() {
        // Server-rail (billing) fraud REVIEW: the pre-authorized charge captures + is recorded,
        // but is NOT held — holding it would surface as requires_capture and trip dunning (M1).
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(review());
        when(delegate.createPayment(any())).thenReturn(resp("pay_b", "automatic"));

        gateway.createPayment(req(Map.of("tenant_id", "t1", "source", "billing_subscription")));

        ArgumentCaptor<PaymentRequest> sent = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(delegate).createPayment(sent.capture());
        assertThat(sent.getValue().captureMethod()).isEqualTo("automatic"); // NOT forced to manual
        verify(holds, never()).hold(any(), any(), any());                   // no hold on a server rail
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
}
