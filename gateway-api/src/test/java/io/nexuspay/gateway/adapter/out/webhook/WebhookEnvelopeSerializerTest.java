package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INT-1: unit tests for {@link WebhookEnvelopeSerializer} — the pure outbox→canonical-envelope transform.
 * Each test fails if the transform is reverted (e.g. the raw outbox payload is passed through unchanged).
 */
class WebhookEnvelopeSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebhookEnvelopeSerializer serializer = new WebhookEnvelopeSerializer(objectMapper);

    private static final String CAPTURE_OUTBOX =
            "{\"event_id\":\"evt_internal_9f\",\"event_type\":\"PaymentCaptured\","
            + "\"aggregate_type\":\"Payment\",\"aggregate_id\":\"pay_abc123\","
            + "\"timestamp\":\"2026-06-15T14:03:22.511Z\",\"version\":1,"
            + "\"metadata\":{\"source\":\"hyperswitch_webhook\",\"original_event_id\":\"hs_evt_77\"},"
            + "\"payload\":{\"payment_id\":\"pay_abc123\",\"amount\":4999,\"currency\":\"USD\","
            + "\"status\":\"succeeded\",\"customer_id\":\"cus_55\",\"capture_method\":\"automatic\"}}";

    private static final String REFUND_OUTBOX =
            "{\"event_id\":\"evt_internal_r\",\"event_type\":\"RefundCompleted\","
            + "\"aggregate_type\":\"Refund\",\"aggregate_id\":\"pay_abc123\","
            + "\"timestamp\":\"2026-06-15T15:00:00Z\",\"version\":1,"
            + "\"metadata\":{\"source\":\"hyperswitch_webhook\",\"original_event_id\":\"hs_evt_91\"},"
            + "\"payload\":{\"refund_id\":\"ref_z9\",\"payment_id\":\"pay_abc123\",\"amount\":4999,"
            + "\"currency\":\"USD\",\"status\":\"succeeded\"}}";

    private JsonNode tree(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private JsonNode serializeToTree(String outbox, String dotted, Map<String, Object> meta) throws Exception {
        return objectMapper.readTree(serializer.serialize(tree(outbox), dotted, meta));
    }

    @Test
    void canonicalShape_hasExactlyTheContractFields() throws Exception {
        JsonNode env = serializeToTree(CAPTURE_OUTBOX, "payment.succeeded", Map.of());

        // INT-3: the additive top-level livemode field sits between type and created (Stripe parity).
        assertThat(env.fieldNames()).toIterable()
                .containsExactly("id", "type", "livemode", "created", "api_version", "data");
        assertThat(env.path("api_version").asText()).isEqualTo("2026-06-16");
        assertThat(env.path("data").fieldNames()).toIterable().containsExactly("object", "metadata");
        // INT-3: with no __livemode in the metadata, livemode defaults to true (a real payment).
        assertThat(env.path("livemode").isBoolean()).isTrue();
        assertThat(env.path("livemode").asBoolean()).isTrue();
        // If the transform were reverted (raw outbox passed through), there would be no api_version.
        assertThat(env.has("api_version")).isTrue();
        assertThat(env.has("payload")).as("raw outbox 'payload' must NOT appear at top level").isFalse();
    }

    @ParameterizedTest(name = "{0} -> data.object.object={1}, type kept")
    @CsvSource({
            "Payment,payment",
            "Refund,refund"
    })
    void dataObject_discriminatorByAggregateType(String aggregateType, String expectedObject) throws Exception {
        String outbox = aggregateType.equals("Refund") ? REFUND_OUTBOX : CAPTURE_OUTBOX;
        String dotted = aggregateType.equals("Refund") ? "payment.refunded" : "payment.succeeded";

        JsonNode env = serializeToTree(outbox, dotted, Map.of());

        assertThat(env.path("data").path("object").path("object").asText()).isEqualTo(expectedObject);
    }

    @Test
    void dottedType_isCarriedVerbatim() throws Exception {
        assertThat(serializeToTree(CAPTURE_OUTBOX, "payment.succeeded", Map.of()).path("type").asText())
                .isEqualTo("payment.succeeded");
        assertThat(serializeToTree(REFUND_OUTBOX, "payment.refunded", Map.of()).path("type").asText())
                .isEqualTo("payment.refunded");
    }

    @Test
    void stableId_prefersOriginalEventId() throws Exception {
        assertThat(serializeToTree(CAPTURE_OUTBOX, "payment.succeeded", Map.of()).path("id").asText())
                .isEqualTo("hs_evt_77");
    }

    @Test
    void stableId_fallsBackToEventId_thenAggregateId() throws Exception {
        String noOriginal = "{\"event_id\":\"evt_X\",\"aggregate_type\":\"Payment\","
                + "\"aggregate_id\":\"pay_1\",\"timestamp\":\"2026-06-15T14:03:22Z\","
                + "\"metadata\":{\"source\":\"s\"},\"payload\":{\"payment_id\":\"pay_1\"}}";
        assertThat(serializeToTree(noOriginal, "payment.succeeded", Map.of()).path("id").asText())
                .isEqualTo("evt_X");

        String onlyAggregate = "{\"aggregate_type\":\"Payment\",\"aggregate_id\":\"pay_2\","
                + "\"timestamp\":\"2026-06-15T14:03:22Z\",\"payload\":{}}";
        assertThat(serializeToTree(onlyAggregate, "payment.succeeded", Map.of()).path("id").asText())
                .isEqualTo("pay_2");
    }

    @Test
    void stableId_isDeterministic_noPerSendMint() throws Exception {
        String first = serializeToTree(CAPTURE_OUTBOX, "payment.succeeded", Map.of()).path("id").asText();
        String second = serializeToTree(CAPTURE_OUTBOX, "payment.succeeded", Map.of()).path("id").asText();
        assertThat(first).isEqualTo(second).isEqualTo("hs_evt_77");
    }

    @Test
    void created_isEpochSeconds_notMillisNorIso() throws Exception {
        JsonNode env = serializeToTree(CAPTURE_OUTBOX, "payment.succeeded", Map.of());
        // 2026-06-15T14:03:22.511Z -> 1781532202 (epoch SECONDS, sub-second truncated).
        assertThat(env.path("created").isIntegralNumber()).isTrue();
        assertThat(env.path("created").asLong()).isEqualTo(1781532202L);
    }

    @Test
    void dataObject_normalizesIdAndStripsCardSubtree() throws Exception {
        String withCard = "{\"event_id\":\"e\",\"aggregate_type\":\"Payment\",\"aggregate_id\":\"pay_9\","
                + "\"timestamp\":\"2026-06-15T14:03:22Z\",\"metadata\":{\"original_event_id\":\"o\"},"
                + "\"payload\":{\"payment_id\":\"pay_9\",\"amount\":100,"
                + "\"payment_method_data\":{\"number\":\"4111111111111111\"},"
                + "\"card\":{\"last4\":\"1111\"}}}";

        JsonNode object = serializeToTree(withCard, "payment.succeeded", Map.of()).path("data").path("object");

        assertThat(object.path("id").asText()).isEqualTo("pay_9");
        assertThat(object.path("object").asText()).isEqualTo("payment");
        assertThat(object.path("amount").asInt()).isEqualTo(100);
        assertThat(object.has("payment_method_data")).as("PAN subtree must be stripped").isFalse();
        assertThat(object.has("card")).as("card subtree must be stripped").isFalse();
    }

    @Test
    void refundObject_idIsRefundId_keepsPaymentId() throws Exception {
        JsonNode object = serializeToTree(REFUND_OUTBOX, "payment.refunded", Map.of())
                .path("data").path("object");
        assertThat(object.path("id").asText()).isEqualTo("ref_z9");
        assertThat(object.path("object").asText()).isEqualTo("refund");
        assertThat(object.path("payment_id").asText()).isEqualTo("pay_abc123");
    }

    @Test
    void metadata_roundTrips_andEmptyIsPresentNotNull() throws Exception {
        JsonNode withMeta = serializeToTree(CAPTURE_OUTBOX, "payment.succeeded",
                Map.of("userId", "u_42", "packId", "gold"));
        JsonNode meta = withMeta.path("data").path("metadata");
        assertThat(meta.path("userId").asText()).isEqualTo("u_42");
        assertThat(meta.path("packId").asText()).isEqualTo("gold");

        JsonNode empty = serializeToTree(CAPTURE_OUTBOX, "payment.succeeded", Map.of());
        assertThat(empty.path("data").has("metadata")).isTrue();
        assertThat(empty.path("data").path("metadata").isObject()).isTrue();
        assertThat(empty.path("data").path("metadata").isEmpty()).isTrue();
        assertThat(empty.path("data").path("metadata").isNull()).isFalse();
    }

    @Test
    void created_fallsBackToNow_whenTimestampUnparseable() throws Exception {
        String bad = "{\"aggregate_type\":\"Payment\",\"aggregate_id\":\"pay_1\","
                + "\"timestamp\":\"not-a-time\",\"payload\":{\"payment_id\":\"pay_1\"}}";
        long before = java.time.Instant.now().getEpochSecond();
        long created = serializeToTree(bad, "payment.succeeded", Map.of()).path("created").asLong();
        long after = java.time.Instant.now().getEpochSecond();
        assertThat(created).isBetween(before, after);
    }

    @Test
    void allEightMappings_carryTheirDottedType() throws Exception {
        // Spot-check that arbitrary dotted strings are carried verbatim (the taxonomy is tested separately).
        for (String dotted : List.of("payment.created", "payment.authorized", "payment.succeeded",
                "payment.failed", "payment.canceled", "payment.refund.created", "payment.refunded",
                "payment.refund.failed")) {
            assertThat(serializeToTree(CAPTURE_OUTBOX, dotted, Map.of()).path("type").asText())
                    .isEqualTo(dotted);
        }
    }
}
