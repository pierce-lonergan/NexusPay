package io.nexuspay.gateway.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.gateway.adapter.in.rest.dto.PaymentApiResponse;
import io.nexuspay.payment.domain.PaymentResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Critique 5.2: the {@code POST /v1/payments} REST response must emit a {@code livemode} boolean (so a
 * webhook-side {@code event.livemode} check reuses on the REST side), derived purely from the
 * SERVER-DERIVED {@code mode} — {@code "live"} &rarr; {@code true}, {@code "test"} &rarr; {@code false}.
 *
 * <p>Back-compat invariant: {@code livemode} is a NULLABLE {@code Boolean}, so the no-mode overload
 * {@link ResponseMapper#toPaymentResponse(PaymentResponse)} (which already drops {@code mode} via
 * {@code @JsonInclude(NON_NULL)}) ALSO drops {@code livemode} — today's serialization is unchanged for
 * any caller without an authenticated principal.
 *
 * <p>Each assertion fails if the fix is reverted: dropping the field fails the wire-key checks; deriving
 * {@code livemode} from anything but {@code mode} (or making it a primitive {@code boolean}) breaks the
 * no-mode-overload omission; mapping {@code "test"} to {@code true} fails the test-mode case.
 */
class PaymentApiResponseLivemodeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** {@code created_at} (Instant) is left null so the test needs no JavaTimeModule on the classpath. */
    private static PaymentResponse payment() {
        return new PaymentResponse(
                "pay_test_1", "succeeded", 5000L, "USD", "automatic", "cust_1",
                "mock", "txn_1", null, null, null, Map.of());
    }

    @Test
    void liveMode_serializesLivemodeTrue() throws Exception {
        PaymentApiResponse dto = ResponseMapper.toPaymentResponse(payment(), "live");

        assertThat(dto.mode()).isEqualTo("live");
        assertThat(dto.livemode()).isTrue();

        Map<String, Object> json = serialize(dto);
        assertThat(json).containsEntry("mode", "live");
        assertThat(json).containsEntry("livemode", Boolean.TRUE);
    }

    @Test
    void testMode_serializesLivemodeFalse() throws Exception {
        PaymentApiResponse dto = ResponseMapper.toPaymentResponse(payment(), "test");

        assertThat(dto.mode()).isEqualTo("test");
        assertThat(dto.livemode()).isFalse();

        Map<String, Object> json = serialize(dto);
        assertThat(json).containsEntry("mode", "test");
        assertThat(json).containsEntry("livemode", Boolean.FALSE);
    }

    @Test
    void noModeOverload_omitsBothModeAndLivemode() throws Exception {
        PaymentApiResponse dto = ResponseMapper.toPaymentResponse(payment());

        assertThat(dto.mode()).as("no-mode overload leaves mode null").isNull();
        assertThat(dto.livemode()).as("nullable livemode is null when mode is null").isNull();

        Map<String, Object> json = serialize(dto);
        // NON_NULL drops BOTH — today's serialization is preserved for mode and the new livemode alike.
        assertThat(json).doesNotContainKey("mode");
        assertThat(json).doesNotContainKey("livemode");
        // Sanity: a present, non-null field still serializes (the NON_NULL drop is field-scoped, not global).
        assertThat(json).containsEntry("id", "pay_test_1");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> serialize(PaymentApiResponse dto) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(dto), Map.class);
    }
}
