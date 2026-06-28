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

    /** A TEST-1 create request carrying the reserved {@code __test_outcome} control value. */
    private static PaymentRequest reqWithOutcome(String outcome) {
        return new PaymentRequest(5000, "USD", "cust_42", "card", "4111111111111111",
                null, "desc", "automatic", "idem-1",
                Map.of("userId", "u1", MockPaymentGatewayPort.TEST_OUTCOME_KEY, outcome));
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

    // ---- TEST-1: forced-outcome convention (TEST-MODE ONLY) ----

    @Test
    void forcedDecline_failsWithCardDeclined_andIsStored() {
        PaymentResponse r = mock.createPayment(reqWithOutcome("declined"));

        assertThat(r.gatewayPaymentId()).startsWith("pay_test_");
        assertThat(r.status()).isEqualTo(PaymentResponse.STATUS_FAILED);
        assertThat(r.errorCode()).isEqualTo("card_declined");
        assertThat(r.errorMessage()).isEqualTo("Your card was declined.");
        // amount/currency/customer are still echoed on a failed create.
        assertThat(r.amount()).isEqualTo(5000);
        assertThat(r.currency()).isEqualTo("USD");
        assertThat(r.customerId()).isEqualTo("cust_42");
        // a failed intent is still stored so getPayment works.
        assertThat(mock.getPayment(r.gatewayPaymentId()).status()).isEqualTo(PaymentResponse.STATUS_FAILED);
    }

    @Test
    void forcedInsufficientFunds_failsWithMatchingErrorCode() {
        PaymentResponse r = mock.createPayment(reqWithOutcome("insufficient_funds"));
        assertThat(r.status()).isEqualTo(PaymentResponse.STATUS_FAILED);
        assertThat(r.errorCode()).isEqualTo("insufficient_funds");
        assertThat(r.errorMessage()).isEqualTo("Your card has insufficient funds.");
    }

    @Test
    void forcedExpiredCard_failsWithMatchingErrorCode() {
        PaymentResponse r = mock.createPayment(reqWithOutcome("expired_card"));
        assertThat(r.status()).isEqualTo(PaymentResponse.STATUS_FAILED);
        assertThat(r.errorCode()).isEqualTo("expired_card");
        assertThat(r.errorMessage()).isEqualTo("Your card has expired.");
    }

    @Test
    void forcedOutcome_isCaseInsensitive() {
        assertThat(mock.createPayment(reqWithOutcome("DECLINED")).status())
                .isEqualTo(PaymentResponse.STATUS_FAILED);
        assertThat(mock.createPayment(reqWithOutcome("  Expired_Card  ")).errorCode())
                .isEqualTo("expired_card");
    }

    @Test
    void explicitSucceed_keepsSuccess_byteIdenticalToAbsent() {
        PaymentResponse r = mock.createPayment(reqWithOutcome("succeed"));
        assertThat(r.status()).isEqualTo(PaymentResponse.STATUS_SUCCEEDED);
        assertThat(r.errorCode()).isNull();
        assertThat(r.errorMessage()).isNull();
    }

    @Test
    void unknownOutcome_doesNotFail_defaultsToSuccess() {
        // A typo must NOT silently break a happy-path test — unknown -> normal success.
        PaymentResponse r = mock.createPayment(reqWithOutcome("declyned"));
        assertThat(r.status()).isEqualTo(PaymentResponse.STATUS_SUCCEEDED);
        assertThat(r.errorCode()).isNull();
    }

    @Test
    void absentOutcome_isUnchangedSuccess() {
        PaymentResponse r = mock.createPayment(req("automatic"));
        assertThat(r.status()).isEqualTo(PaymentResponse.STATUS_SUCCEEDED);
        assertThat(r.errorCode()).isNull();
    }

    @Test
    void forcedRefundFailure_viaMagicAmount_failsWithRefundFailedCode() {
        // amount % 100 == 66 -> forced refund failure (the documented sentinel).
        RefundResponse r = mock.createRefund(new RefundRequest("pay_test_1", 1066, "USD", "dup", "k"));

        assertThat(r.gatewayRefundId()).startsWith("re_test_");
        assertThat(r.status()).isEqualTo(RefundResponse.STATUS_FAILED);
        assertThat(r.errorCode()).isEqualTo("refund_failed");
        assertThat(r.errorMessage()).isNotBlank();
        assertThat(mock.getRefund(r.gatewayRefundId()).status()).isEqualTo(RefundResponse.STATUS_FAILED);
    }

    @Test
    void refundWithoutSentinelAmount_isUnchangedSuccess() {
        // 4999 % 100 == 99 -> not the sentinel -> succeeds (existing behavior).
        RefundResponse r = mock.createRefund(new RefundRequest("pay_test_1", 4999, "USD", "dup", "k"));
        assertThat(r.status()).isEqualTo(RefundResponse.STATUS_SUCCEEDED);
        assertThat(r.errorCode()).isNull();
    }

    @Test
    void twoCreates_produceDistinctIds() {
        assertThat(mock.createPayment(req("automatic")).gatewayPaymentId())
                .isNotEqualTo(mock.createPayment(req("automatic")).gatewayPaymentId());
    }

    // ---- GAP-078 (SHOULD_FIX): restampCreatedAt mutates the STORE so single-retrieve agrees ----

    @Test
    void restampCreatedAt_payment_mutatesStore_soGetPaymentReturnsFrozen() {
        java.time.Instant frozen = java.time.Instant.parse("2026-01-01T00:00:00Z");
        PaymentResponse created = mock.createPayment(req("automatic"));
        // Pre-condition: the stored createdAt is the mock's real-time stamp, NOT the frozen instant.
        assertThat(mock.getPayment(created.gatewayPaymentId()).createdAt()).isNotEqualTo(frozen);

        mock.restampCreatedAt(created.gatewayPaymentId(), frozen);

        // Single-retrieve (the GET /v1/payments/{id} read path) now returns the frozen instant — the store
        // and the create response agree (the SHOULD_FIX read-path consistency guarantee).
        assertThat(mock.getPayment(created.gatewayPaymentId()).createdAt()).isEqualTo(frozen);
        // Every other field is preserved (status/amount/id untouched).
        assertThat(mock.getPayment(created.gatewayPaymentId()).status())
                .isEqualTo(PaymentResponse.STATUS_SUCCEEDED);
    }

    @Test
    void restampCreatedAt_refund_mutatesStore_soGetRefundReturnsFrozen() {
        java.time.Instant frozen = java.time.Instant.parse("2026-01-01T00:00:00Z");
        RefundResponse created = mock.createRefund(new RefundRequest("pay_test_1", 4999, "USD", "dup", "k"));
        assertThat(mock.getRefund(created.gatewayRefundId()).createdAt()).isNotEqualTo(frozen);

        mock.restampCreatedAt(created.gatewayRefundId(), frozen);

        assertThat(mock.getRefund(created.gatewayRefundId()).createdAt()).isEqualTo(frozen);
        assertThat(mock.getRefund(created.gatewayRefundId()).status())
                .isEqualTo(RefundResponse.STATUS_SUCCEEDED);
    }

    @Test
    void restampCreatedAt_isNoOp_forUnknownIdOrNulls() {
        // Unknown id / null id / null instant must never throw or resurrect a key (defensive guards).
        java.time.Instant frozen = java.time.Instant.parse("2026-01-01T00:00:00Z");
        mock.restampCreatedAt("pay_test_never_minted", frozen); // no-op, no throw
        mock.restampCreatedAt(null, frozen);                     // no-op, no throw
        PaymentResponse created = mock.createPayment(req("automatic"));
        java.time.Instant original = mock.getPayment(created.gatewayPaymentId()).createdAt();
        mock.restampCreatedAt(created.gatewayPaymentId(), null);  // null instant -> store unchanged
        assertThat(mock.getPayment(created.gatewayPaymentId()).createdAt()).isEqualTo(original);
        // Unknown id was NOT created in the store as a side effect.
        assertThatThrownBy(() -> mock.getPayment("pay_test_never_minted"))
                .isInstanceOf(PaymentException.class);
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
