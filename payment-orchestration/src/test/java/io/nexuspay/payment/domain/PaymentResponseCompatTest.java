package io.nexuspay.payment.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-6 (A3) back-compat guard: the additive {@code nextAction} 13th record component must not break the
 * 16 existing {@code new PaymentResponse(...)} 12-arg call sites. The 12-arg convenience constructor must
 * yield a {@code null} nextAction and be field-for-field equal to the canonical 13-arg constructor with a
 * null nextAction. Mirrors the TEST-3c {@code PaymentRequest} 10-arg compat-ctor lesson.
 */
class PaymentResponseCompatTest {

    private static final Instant TS = Instant.parse("2026-06-27T00:00:00Z");
    private static final Map<String, Object> META = Map.of("userId", "u1");

    private static PaymentResponse twelveArg(String status) {
        return new PaymentResponse(
                "pay_test_1", status, 5000, "USD", "automatic", "cust_1",
                "mock", "txn_test_1", null, null, TS, META);
    }

    private static PaymentResponse thirteenArg(String status, PaymentResponse.NextAction na) {
        return new PaymentResponse(
                "pay_test_1", status, 5000, "USD", "automatic", "cust_1",
                "mock", "txn_test_1", null, null, TS, META, na);
    }

    @Test
    void twelveArgCtor_yieldsNullNextAction_andEqualsThirteenArgWithNull() {
        PaymentResponse twelve = twelveArg(PaymentResponse.STATUS_SUCCEEDED);

        assertThat(twelve.nextAction()).isNull();
        assertThat(twelve).isEqualTo(thirteenArg(PaymentResponse.STATUS_SUCCEEDED, null));
    }

    @Test
    void requiresAction_trueOnlyForRequiresActionStatus() {
        assertThat(twelveArg(PaymentResponse.STATUS_REQUIRES_ACTION).requiresAction()).isTrue();
        assertThat(twelveArg(PaymentResponse.STATUS_SUCCEEDED).requiresAction()).isFalse();
        assertThat(twelveArg(PaymentResponse.STATUS_REQUIRES_CAPTURE).requiresAction()).isFalse();
    }

    @Test
    void withNextAction_setsField_andPreservesAllOthers() {
        PaymentResponse base = twelveArg(PaymentResponse.STATUS_REQUIRES_ACTION);
        PaymentResponse.NextAction na =
                new PaymentResponse.NextAction("redirect_to_url", "https://test.nexuspay.local/3ds/pay_test_1");

        PaymentResponse copy = base.withNextAction(na);

        assertThat(copy.nextAction()).isEqualTo(na);
        // every other field is preserved.
        assertThat(copy.gatewayPaymentId()).isEqualTo(base.gatewayPaymentId());
        assertThat(copy.status()).isEqualTo(base.status());
        assertThat(copy.amount()).isEqualTo(base.amount());
        assertThat(copy.currency()).isEqualTo(base.currency());
        assertThat(copy.captureMethod()).isEqualTo(base.captureMethod());
        assertThat(copy.customerId()).isEqualTo(base.customerId());
        assertThat(copy.connectorName()).isEqualTo(base.connectorName());
        assertThat(copy.connectorTransactionId()).isEqualTo(base.connectorTransactionId());
        assertThat(copy.errorCode()).isEqualTo(base.errorCode());
        assertThat(copy.errorMessage()).isEqualTo(base.errorMessage());
        assertThat(copy.createdAt()).isEqualTo(base.createdAt());
        assertThat(copy.metadata()).isEqualTo(base.metadata());
        // the base is unchanged (copy semantics).
        assertThat(base.nextAction()).isNull();
    }

    @Test
    void nextActionRecord_isTwoStringFields_noInstant() {
        PaymentResponse.NextAction na = new PaymentResponse.NextAction("redirect_to_url", "https://x/y");
        assertThat(na.type()).isEqualTo("redirect_to_url");
        assertThat(na.url()).isEqualTo("https://x/y");
    }
}
