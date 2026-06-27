package io.nexuspay.payment.adapter.out.hyperswitch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.payment.domain.PaymentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * TEST-3c: the {@link HyperSwitchPaymentAdapter} forwards the off-session fields onto the wire
 * ({@code off_session}/{@code setup_future_usage}/{@code mandate_id}/{@code payment_method}), and a
 * request with all four null produces a body byte-identical to pre-3c (the NON_NULL DTO omits them).
 *
 * <p>Captures the ACTUAL request body the adapter sends via {@link MockRestServiceServer} bound to the
 * adapter's {@link RestClient}, then parses it (L-071: a module-registered {@link ObjectMapper}; no
 * hardcoded server-generated id is asserted — we assert the request fields).</p>
 */
class HyperSwitchPaymentAdapterOffSessionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    /** A minimal successful HyperSwitch payment response body so the adapter's mapper does not blow up. */
    private static final String OK_RESPONSE = """
            {"payment_id":"pay_hs_1","status":"succeeded","amount":5000,"currency":"USD",
             "connector":"stripe"}
            """;

    private record Harness(HyperSwitchPaymentAdapter adapter, AtomicReference<String> body) {}

    private static Harness harness() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://hs.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicReference<String> captured = new AtomicReference<>();
        server.expect(requestTo("http://hs.test/payments"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(request -> {
                    captured.set(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                            .getBodyAsString());
                    return withSuccess(OK_RESPONSE, MediaType.APPLICATION_JSON).createResponse(request);
                });
        return new Harness(new HyperSwitchPaymentAdapter(builder.build()), captured);
    }

    @Test
    void offSessionFields_areForwardedOnTheWire() throws Exception {
        Harness h = harness();
        PaymentRequest req = new PaymentRequest(5000, "USD", "cus_1", "card", null, null, "desc",
                "automatic", "idem-1", Map.of(),
                "pmref_test_pm_card_visa", Boolean.TRUE, "off_session", "mandate_123");

        h.adapter().createPayment(req);

        JsonNode body = MAPPER.readTree(h.body().get());
        assertThat(body.get("payment_method").asText()).isEqualTo("pmref_test_pm_card_visa");
        assertThat(body.get("off_session").asBoolean()).isTrue();
        assertThat(body.get("setup_future_usage").asText()).isEqualTo("off_session");
        assertThat(body.get("mandate_id").asText()).isEqualTo("mandate_123");
        assertThat(body.get("customer_id").asText()).isEqualTo("cus_1");
    }

    @Test
    void inlineCardRequest_omitsOffSessionFields_byteIdentical() throws Exception {
        Harness h = harness();
        // The 10-arg compat ctor -> all four off-session fields null (the pre-3c inline-card create).
        PaymentRequest req = new PaymentRequest(5000, "USD", "cus_1", "card", null, null, "desc",
                "automatic", "idem-1", Map.of());

        h.adapter().createPayment(req);

        JsonNode body = MAPPER.readTree(h.body().get());
        // NON_NULL omits every off-session field -> the wire is identical to pre-3c.
        assertThat(body.has("payment_method")).isFalse();
        assertThat(body.has("off_session")).isFalse();
        assertThat(body.has("setup_future_usage")).isFalse();
        assertThat(body.has("mandate_id")).isFalse();
    }
}
