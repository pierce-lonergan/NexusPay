package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.PaymentApiResponse;
import io.nexuspay.payment.domain.PaymentResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-6 (A3): {@link ResponseMapper#toPaymentResponse} surfaces the TYPED domain {@code nextAction} onto
 * {@link PaymentApiResponse#next_action} for a requires_action payment, and drops it (null) for a plain
 * success/decline. The mapper reads the typed domain field directly — NOT the INT-6
 * metadata-derived path used by {@code toConfirmResponse}.
 */
class ResponseMapperNextActionTest {

    private static PaymentResponse base(String status, PaymentResponse.NextAction na) {
        return new PaymentResponse(
                "pay_test_1", status, 5000, "USD", "automatic", "cust_1",
                "mock", "txn_test_1", null, null, Instant.parse("2026-06-27T00:00:00Z"),
                Map.of(), na);
    }

    @Test
    void requiresAction_surfacesNextAction() {
        PaymentResponse.NextAction na =
                new PaymentResponse.NextAction("redirect_to_url", "https://test.nexuspay.local/3ds/pay_test_1");
        PaymentApiResponse dto = ResponseMapper.toPaymentResponse(base(PaymentResponse.STATUS_REQUIRES_ACTION, na), "test");

        assertThat(dto.status()).isEqualTo(PaymentResponse.STATUS_REQUIRES_ACTION);
        assertThat(dto.next_action()).isNotNull();
        assertThat(dto.next_action().type()).isEqualTo("redirect_to_url");
        assertThat(dto.next_action().url()).isEqualTo("https://test.nexuspay.local/3ds/pay_test_1");
    }

    @Test
    void success_dropsNextAction() {
        PaymentApiResponse dto = ResponseMapper.toPaymentResponse(base(PaymentResponse.STATUS_SUCCEEDED, null), "test");
        assertThat(dto.next_action()).isNull();
    }

    @Test
    void decline_dropsNextAction() {
        PaymentResponse declined = new PaymentResponse(
                "pay_test_1", PaymentResponse.STATUS_FAILED, 5000, "USD", "automatic", "cust_1",
                "mock", "txn_test_1", "card_declined", "Your card was declined.",
                Instant.parse("2026-06-27T00:00:00Z"), Map.of());
        PaymentApiResponse dto = ResponseMapper.toPaymentResponse(declined, "test");
        assertThat(dto.next_action()).isNull();
    }

    @Test
    void doesNotUseMetadataNextActionPath() {
        // A success payment that happens to carry a metadata.next_action map must NOT surface next_action
        // on the payment DTO — only the TYPED domain field drives it (the metadata path is confirm-specific).
        PaymentResponse withMeta = new PaymentResponse(
                "pay_test_1", PaymentResponse.STATUS_SUCCEEDED, 5000, "USD", "automatic", "cust_1",
                "mock", "txn_test_1", null, null, Instant.parse("2026-06-27T00:00:00Z"),
                Map.of("next_action", Map.of("type", "redirect_to_url", "url", "https://leak/x")));
        PaymentApiResponse dto = ResponseMapper.toPaymentResponse(withMeta, "test");
        assertThat(dto.next_action()).isNull();
    }
}
