package io.nexuspay.payment.adapter.out.mock;

import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.payment.domain.CaptureRequest;
import io.nexuspay.payment.domain.ConfirmRequest;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.RefundResponse;
import io.nexuspay.payment.domain.VoidRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INT-3 (T4): {@link MockPaymentGatewayPort} is a deterministic, network-free fake PSP. Each assertion
 * fails if the mock behavior is reverted.
 */
class MockPaymentGatewayPortTest {

    private final MockPaymentGatewayPort mock = new MockPaymentGatewayPort();

    private static PaymentRequest req(String captureMethod) {
        return new PaymentRequest(5000, "USD", "cust_42", "card", "4111111111111111",
                null, "desc", captureMethod, "idem-1", Map.of("userId", "u1", "packId", "p1"));
    }

    @Test
    void autoCaptureCreate_isSucceeded_withTestIdAndEchoes() {
        PaymentResponse r = mock.createPayment(req("automatic"));

        assertThat(r.gatewayPaymentId()).startsWith("pay_test_");
        assertThat(r.status()).isEqualTo(PaymentResponse.STATUS_SUCCEEDED);
        assertThat(r.amount()).isEqualTo(5000);
        assertThat(r.currency()).isEqualTo("USD");
        assertThat(r.customerId()).isEqualTo("cust_42");
        assertThat(r.connectorName()).isEqualTo(MockPaymentGatewayPort.CONNECTOR);
        assertThat(r.metadata()).containsEntry("userId", "u1").containsEntry("packId", "p1");
    }

    @Test
    void manualCreate_isRequiresCapture() {
        PaymentResponse r = mock.createPayment(req("manual"));

        assertThat(r.gatewayPaymentId()).startsWith("pay_test_");
        assertThat(r.status()).isEqualTo(PaymentResponse.STATUS_REQUIRES_CAPTURE);
        assertThat(r.captureMethod()).isEqualTo("manual");
    }

    @Test
    void capture_transitionsToSucceeded_andHonorsPartialAmount() {
        PaymentResponse created = mock.createPayment(req("manual"));

        PaymentResponse captured = mock.capturePayment(created.gatewayPaymentId(),
                new CaptureRequest(2500L, "k"));

        assertThat(captured.status()).isEqualTo(PaymentResponse.STATUS_SUCCEEDED);
        assertThat(captured.amount()).as("partial capture amount is echoed").isEqualTo(2500);
    }

    @Test
    void confirm_autoIntent_settles_manualIntent_staysRequiresCapture() {
        PaymentResponse auto = mock.createPayment(req("automatic"));
        PaymentResponse manual = mock.createPayment(req("manual"));

        assertThat(mock.confirmPayment(auto.gatewayPaymentId(), new ConfirmRequest(null, null, null, "k"))
                .status()).isEqualTo(PaymentResponse.STATUS_SUCCEEDED);
        assertThat(mock.confirmPayment(manual.gatewayPaymentId(), new ConfirmRequest(null, null, null, "k"))
                .status()).isEqualTo(PaymentResponse.STATUS_REQUIRES_CAPTURE);
    }

    @Test
    void voidPayment_transitionsToCancelled() {
        PaymentResponse created = mock.createPayment(req("manual"));

        PaymentResponse voided = mock.voidPayment(created.gatewayPaymentId(), new VoidRequest("r", "k"));

        assertThat(voided.status()).isEqualTo(PaymentResponse.STATUS_CANCELLED);
    }

    @Test
    void getPayment_returnsStored_andUnknownIsNotFound() {
        PaymentResponse created = mock.createPayment(req("automatic"));

        assertThat(mock.getPayment(created.gatewayPaymentId()).gatewayPaymentId())
                .isEqualTo(created.gatewayPaymentId());
        assertThatThrownBy(() -> mock.getPayment("pay_test_missing"))
                .isInstanceOfSatisfying(PaymentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("payment_not_found"));
    }

    @Test
    void refund_isSucceeded_withTestId_andEchoes() {
        RefundResponse r = mock.createRefund(new RefundRequest("pay_test_1", 4999, "USD", "duplicate", "k"));

        assertThat(r.gatewayRefundId()).startsWith("re_test_");
        assertThat(r.status()).isEqualTo(RefundResponse.STATUS_SUCCEEDED);
        assertThat(r.paymentId()).isEqualTo("pay_test_1");
        assertThat(r.amount()).isEqualTo(4999);
        assertThat(r.currency()).isEqualTo("USD");
        assertThat(r.reason()).isEqualTo("duplicate");
        assertThat(mock.getRefund(r.gatewayRefundId()).gatewayRefundId()).isEqualTo(r.gatewayRefundId());
    }

    @Test
    void twoCreates_produceDistinctIds() {
        assertThat(mock.createPayment(req("automatic")).gatewayPaymentId())
                .isNotEqualTo(mock.createPayment(req("automatic")).gatewayPaymentId());
    }

    /**
     * INT-3 (ArchUnit substitute): the mock must do ZERO network I/O — assert it imports NO
     * {@code HyperSwitch*} type and NO HTTP client ({@code RestClient}/{@code httpclient}). Reading the
     * source keeps this self-contained (no ArchUnit dependency). Fails if a network collaborator is wired
     * into the mock, which would break the "test key never reaches a real PSP" guarantee.
     */
    @Test
    void mockImportsNoHyperSwitchNorHttpClient() throws IOException {
        Path src = Path.of("src/main/java/io/nexuspay/payment/adapter/out/mock/MockPaymentGatewayPort.java");
        assertThat(Files.exists(src)).as("mock source present at %s", src.toAbsolutePath()).isTrue();
        String code = Files.readString(src, StandardCharsets.UTF_8);
        for (String line : code.split("\n")) {
            if (line.startsWith("import ")) {
                String lower = line.toLowerCase();
                assertThat(lower)
                        .as("mock must not import a HyperSwitch/HTTP type: %s", line.trim())
                        .doesNotContain("hyperswitch")
                        .doesNotContain("restclient")
                        .doesNotContain("httpclient")
                        .doesNotContain("java.net.http");
            }
        }
    }
}
