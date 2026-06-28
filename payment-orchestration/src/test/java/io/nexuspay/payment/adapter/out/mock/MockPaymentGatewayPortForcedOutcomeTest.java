package io.nexuspay.payment.adapter.out.mock;

import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-6 (A3/A4/A5): the {@link MockPaymentGatewayPort} forced NON-FAILURE outcomes. These let an integrator
 * exercise the 3DS/SCA (requires_action), async-settle (processing), and review-hold→capture (fraud_hold)
 * flows without a real card — TEST-MODE ONLY (reachable only through the mock; an {@code sk_live_} key never
 * reaches it). Regression guards confirm the existing success/decline defaults are byte-identical.
 */
class MockPaymentGatewayPortForcedOutcomeTest {

    private final MockPaymentGatewayPort mock = new MockPaymentGatewayPort();

    private static PaymentRequest reqWithOutcome(String outcome) {
        return new PaymentRequest(5000, "USD", "cust_42", "card", "4111111111111111",
                null, "desc", "automatic", "idem-1",
                Map.of("userId", "u1", MockPaymentGatewayPort.TEST_OUTCOME_KEY, outcome));
    }

    private static PaymentRequest reqAuto() {
        return new PaymentRequest(5000, "USD", "cust_42", "card", "4111111111111111",
                null, "desc", "automatic", "idem-1", Map.of("userId", "u1"));
    }

    // ---- A3: requires_action + next_action ----

    @Test
    void forcedRequiresAction_returnsRequiresAction_withNextActionStub() {
        PaymentResponse r = mock.createPayment(reqWithOutcome("requires_action"));

        assertThat(r.gatewayPaymentId()).startsWith("pay_test_");
        assertThat(r.status()).isEqualTo(PaymentResponse.STATUS_REQUIRES_ACTION);
        assertThat(r.requiresAction()).isTrue();
        assertThat(r.errorCode()).isNull();
        assertThat(r.nextAction()).isNotNull();
        assertThat(r.nextAction().type()).isEqualTo(MockPaymentGatewayPort.NEXT_ACTION_REDIRECT);
        assertThat(r.nextAction().url())
                .startsWith(MockPaymentGatewayPort.TEST_3DS_URL_PREFIX)
                .startsWith("https://test.nexuspay.local/3ds/")
                .contains(r.gatewayPaymentId()); // derived from the minted id, no hardcoded server id (L-071)
        // stored so getPayment round-trips, including next_action.
        PaymentResponse fetched = mock.getPayment(r.gatewayPaymentId());
        assertThat(fetched.status()).isEqualTo(PaymentResponse.STATUS_REQUIRES_ACTION);
        assertThat(fetched.nextAction()).isEqualTo(r.nextAction());
    }

    @Test
    void forcedRequiresAction_isCaseInsensitive() {
        assertThat(mock.createPayment(reqWithOutcome("  Requires_Action  ")).status())
                .isEqualTo(PaymentResponse.STATUS_REQUIRES_ACTION);
    }

    // ---- A4: processing (no next_action, no webhook synthesized here) ----

    @Test
    void forcedProcessing_returnsProcessing_withNoNextAction() {
        PaymentResponse r = mock.createPayment(reqWithOutcome("processing"));

        assertThat(r.status()).isEqualTo(PaymentResponse.STATUS_PROCESSING);
        assertThat(r.requiresAction()).isFalse();
        assertThat(r.nextAction()).isNull();
        assertThat(r.errorCode()).isNull();
        assertThat(mock.getPayment(r.gatewayPaymentId()).status()).isEqualTo(PaymentResponse.STATUS_PROCESSING);
    }

    // ---- A5 (option b): fraud_hold -> requires_capture simulation (no hold row) ----

    @Test
    void forcedFraudHold_returnsRequiresCapture_withNoNextAction() {
        PaymentResponse r = mock.createPayment(reqWithOutcome("fraud_hold"));

        assertThat(r.status()).isEqualTo(PaymentResponse.STATUS_REQUIRES_CAPTURE);
        assertThat(r.requiresCapture()).isTrue();
        assertThat(r.nextAction()).isNull();
        assertThat(r.errorCode()).isNull();
        assertThat(mock.getPayment(r.gatewayPaymentId()).status())
                .isEqualTo(PaymentResponse.STATUS_REQUIRES_CAPTURE);
    }

    // ---- regression: success/decline defaults unchanged, byte-identical ----

    @Test
    void absentOutcome_successHasNoNextAction() {
        PaymentResponse r = mock.createPayment(reqAuto());
        assertThat(r.status()).isEqualTo(PaymentResponse.STATUS_SUCCEEDED);
        assertThat(r.errorCode()).isNull();
        assertThat(r.nextAction()).isNull();
    }

    @Test
    void explicitSucceed_andUnknown_stillSucceed_withNoNextAction() {
        for (String v : new String[] {"succeed", "totally_unknown_value"}) {
            PaymentResponse r = mock.createPayment(reqWithOutcome(v));
            assertThat(r.status()).as("outcome=%s", v).isEqualTo(PaymentResponse.STATUS_SUCCEEDED);
            assertThat(r.errorCode()).isNull();
            assertThat(r.nextAction()).isNull();
        }
    }

    @Test
    void forcedDecline_stillFails_unchanged_withNoNextAction() {
        PaymentResponse r = mock.createPayment(reqWithOutcome("declined"));
        assertThat(r.status()).isEqualTo(PaymentResponse.STATUS_FAILED);
        assertThat(r.errorCode()).isEqualTo("card_declined");
        assertThat(r.nextAction()).isNull();
    }

    // ---- single-source-of-truth: the recognized non-failure token set ----

    @Test
    void forcedNonFailureOutcomes_setMatchesRecognizedTokens() {
        assertThat(MockPaymentGatewayPort.FORCED_NONFAILURE_OUTCOMES)
                .containsExactlyInAnyOrder("requires_action", "processing", "fraud_hold");
        // the failure set and the non-failure set are disjoint (no token forces both).
        assertThat(MockPaymentGatewayPort.FORCED_NONFAILURE_OUTCOMES)
                .doesNotContainAnyElementsOf(MockPaymentGatewayPort.FORCED_PAYMENT_OUTCOMES);
    }
}
