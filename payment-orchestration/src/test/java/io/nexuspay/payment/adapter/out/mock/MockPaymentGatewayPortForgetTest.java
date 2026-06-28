package io.nexuspay.payment.adapter.out.mock;

import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.RefundResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * GAP-077 (critique v3 F4): pins {@link MockPaymentGatewayPort#forgetTestArtifacts} — the sandbox-reset
 * mock-map clear — removes ONLY the exact ids it is given and NEVER the whole map. The global maps are
 * keyed by id (not tenant), so a blanket {@code clear()} would wipe a co-resident tenant's test artifacts;
 * this test proves we never do that.
 */
class MockPaymentGatewayPortForgetTest {

    private final MockPaymentGatewayPort mock = new MockPaymentGatewayPort();

    private PaymentResponse createPayment() {
        return mock.createPayment(new PaymentRequest(5000, "USD", "cus_1", "card",
                "4111111111111111", null, "desc", "automatic", "idem_" + System.nanoTime(), Map.of()));
    }

    @Test
    void forgetsOnlyTheGivenPaymentIds_leavesOthersIntact() {
        PaymentResponse mine = createPayment();
        PaymentResponse foreign = createPayment(); // a co-resident id sharing the global map

        // Reset of MY tenant forgets only MY confirmed id.
        mock.forgetTestArtifacts(List.of(mine.gatewayPaymentId()), List.of());

        // mine is gone (getPayment now 404s)...
        assertThatThrownBy(() -> mock.getPayment(mine.gatewayPaymentId()))
                .isInstanceOf(PaymentException.class);
        // ...the foreign id (another tenant's artifact) survives — the map was NOT clear()-ed.
        assertThat(mock.getPayment(foreign.gatewayPaymentId()).gatewayPaymentId())
                .isEqualTo(foreign.gatewayPaymentId());
    }

    @Test
    void forgetsOnlyTheGivenRefundIds_leavesOthersIntact() {
        PaymentResponse pay = createPayment();
        RefundResponse mine = mock.createRefund(
                new RefundRequest(pay.gatewayPaymentId(), 1000, "USD", "requested_by_customer", "rk_1"));
        RefundResponse foreign = mock.createRefund(
                new RefundRequest(pay.gatewayPaymentId(), 1000, "USD", "requested_by_customer", "rk_2"));

        mock.forgetTestArtifacts(List.of(), List.of(mine.gatewayRefundId()));

        assertThatThrownBy(() -> mock.getRefund(mine.gatewayRefundId())).isInstanceOf(PaymentException.class);
        assertThat(mock.getRefund(foreign.gatewayRefundId()).gatewayRefundId())
                .isEqualTo(foreign.gatewayRefundId());
    }

    @Test
    void nullCollections_areNoOps_doNotThrow() {
        PaymentResponse pay = createPayment();
        assertThatCode(() -> mock.forgetTestArtifacts(null, null)).doesNotThrowAnyException();
        // the payment is untouched by a null clear.
        assertThat(mock.getPayment(pay.gatewayPaymentId())).isNotNull();
    }
}
