package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INT-3 (T8): {@link WebhookEnvelopeSerializer} lifts the reserved server-only {@code __livemode}
 * metadata key to the top-level {@code livemode} field and removes it from the delivered
 * {@code data.metadata}. The flag is therefore SERVER-sourced for both the test path (synthesizer +
 * V4030 store) and the live path (V4030 store). Each assertion fails if the lift/strip is reverted.
 */
class WebhookEnvelopeLivemodeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebhookEnvelopeSerializer serializer = new WebhookEnvelopeSerializer(objectMapper);

    private static final String CAPTURE_OUTBOX =
            "{\"event_id\":\"evt_1\",\"event_type\":\"PaymentCaptured\",\"aggregate_type\":\"Payment\","
            + "\"aggregate_id\":\"pay_test_x\",\"timestamp\":\"2026-06-15T14:03:22Z\",\"version\":1,"
            + "\"metadata\":{\"source\":\"mock_sandbox\",\"original_event_id\":\"evt_o\"},"
            + "\"payload\":{\"payment_id\":\"pay_test_x\",\"amount\":4999,\"currency\":\"USD\","
            + "\"status\":\"succeeded\"}}";

    private JsonNode serialize(Map<String, Object> meta) throws Exception {
        return objectMapper.readTree(
                serializer.serialize(objectMapper.readTree(CAPTURE_OUTBOX), "payment.succeeded", meta));
    }

    private static Map<String, Object> metaWith(Object livemodeValue, Map<String, Object> rest) {
        Map<String, Object> m = new LinkedHashMap<>(rest);
        if (livemodeValue != null) {
            m.put("__livemode", livemodeValue);
        }
        return m;
    }

    @Test
    void topLevelKeyOrder_includesLivemode() throws Exception {
        JsonNode env = serialize(metaWith(false, Map.of()));
        assertThat(env.fieldNames()).toIterable()
                .containsExactly("id", "type", "livemode", "created", "api_version", "data");
    }

    @Test
    void livemodeFalse_isLifted_andStrippedFromMetadata() throws Exception {
        JsonNode env = serialize(metaWith(false, Map.of("userId", "u1", "packId", "p1")));

        assertThat(env.path("livemode").isBoolean()).isTrue();
        assertThat(env.path("livemode").asBoolean()).isFalse();
        JsonNode merchantMeta = env.path("data").path("metadata");
        assertThat(merchantMeta.has("__livemode")).as("reserved key must not leak to merchant").isFalse();
        assertThat(merchantMeta.path("userId").asText()).isEqualTo("u1");
        assertThat(merchantMeta.path("packId").asText()).isEqualTo("p1");
    }

    @Test
    void livemodeTrue_isLifted() throws Exception {
        JsonNode env = serialize(metaWith(true, Map.of()));
        assertThat(env.path("livemode").asBoolean()).isTrue();
        assertThat(env.path("data").path("metadata").has("__livemode")).isFalse();
    }

    @Test
    void absentLivemode_defaultsTrue() throws Exception {
        JsonNode env = serialize(Map.of("userId", "u1"));
        assertThat(env.path("livemode").asBoolean()).as("legacy payment with no flag -> live").isTrue();
        assertThat(env.path("data").path("metadata").path("userId").asText()).isEqualTo("u1");
    }

    @Test
    void stringFalse_isParsedAsFalse() throws Exception {
        // Robust to JSON round-tripping through the V4030 store that may stringify the boolean.
        JsonNode env = serialize(metaWith("false", Map.of()));
        assertThat(env.path("livemode").asBoolean()).isFalse();
    }
}
