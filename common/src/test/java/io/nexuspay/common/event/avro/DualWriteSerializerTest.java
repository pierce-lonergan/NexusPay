package io.nexuspay.common.event.avro;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DualWriteSerializer} and the paired {@link DualFormatDeserializer}.
 *
 * <p>The payload-format header these stamp is the security/integrity-relevant routing signal
 * that the deserializer trusts to decide how to decode every event. A wrong or absent header
 * routes a financial message to the wrong decoder.
 */
class DualWriteSerializerTest {

    private static final String TOPIC = "payments.events";

    private String headerValue(Headers headers, String key) {
        Header h = headers.lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    // ---------- configure() toggles the stamped format ----------

    @Test
    void disabledByDefaultStampsJsonFormat() {
        DualWriteSerializer ser = new DualWriteSerializer();
        ser.configure(new HashMap<>(), false); // no dual-write key

        Headers headers = new RecordHeaders();
        byte[] value = ser.serialize(TOPIC, headers, "{\"a\":1}");

        assertEquals(DualWriteSerializer.FORMAT_JSON, headerValue(headers, DualWriteSerializer.HEADER_PAYLOAD_FORMAT));
        // Value is always the JSON bytes, backward compatible
        byte[] expected = new StringSerializer().serialize(TOPIC, "{\"a\":1}");
        assertArrayEquals(expected, value);
    }

    @Test
    void dualWriteEnabledStampsDualFormat() {
        DualWriteSerializer ser = new DualWriteSerializer();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(DualWriteSerializer.DUAL_WRITE_ENABLED_CONFIG, "true");
        ser.configure(cfg, false);

        Headers headers = new RecordHeaders();
        byte[] value = ser.serialize(TOPIC, headers, "{\"a\":1}");

        assertEquals(DualWriteSerializer.FORMAT_DUAL, headerValue(headers, DualWriteSerializer.HEADER_PAYLOAD_FORMAT));
        // Even in DUAL mode the primary value remains JSON
        assertArrayEquals(new StringSerializer().serialize(TOPIC, "{\"a\":1}"), value);
    }

    @Test
    void dualWriteFalseStringStampsJsonFormat() {
        DualWriteSerializer ser = new DualWriteSerializer();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(DualWriteSerializer.DUAL_WRITE_ENABLED_CONFIG, "false");
        ser.configure(cfg, false);

        Headers headers = new RecordHeaders();
        ser.serialize(TOPIC, headers, "{}");

        assertEquals(DualWriteSerializer.FORMAT_JSON, headerValue(headers, DualWriteSerializer.HEADER_PAYLOAD_FORMAT));
    }

    @Test
    void noHeadersOverloadReturnsJsonBytes() {
        DualWriteSerializer ser = new DualWriteSerializer();
        ser.configure(new HashMap<>(), false);

        byte[] value = ser.serialize(TOPIC, "{\"k\":\"v\"}");
        assertArrayEquals(new StringSerializer().serialize(TOPIC, "{\"k\":\"v\"}"), value);
    }

    // ---------- static header utilities ----------

    @Test
    void attachJsonHeaderAddsBothJsonPayloadAndAvroFormat() {
        Headers headers = new RecordHeaders();
        DualWriteSerializer.attachJsonHeader(headers, "{\"x\":1}");

        assertEquals("{\"x\":1}", headerValue(headers, DualWriteSerializer.HEADER_JSON_PAYLOAD));
        assertEquals(DualWriteSerializer.FORMAT_AVRO, headerValue(headers, DualWriteSerializer.HEADER_PAYLOAD_FORMAT));
    }

    @Test
    void extractFormatReturnsHeaderValue() {
        Headers headers = new RecordHeaders();
        headers.add(new RecordHeader(DualWriteSerializer.HEADER_PAYLOAD_FORMAT,
                DualWriteSerializer.FORMAT_AVRO.getBytes(StandardCharsets.UTF_8)));

        assertEquals(DualWriteSerializer.FORMAT_AVRO, DualWriteSerializer.extractFormat(headers));
    }

    @Test
    void extractFormatDefaultsToJsonWhenHeaderAbsent() {
        // Security/compat invariant: legacy messages with no format header are treated as JSON.
        Headers headers = new RecordHeaders();
        assertEquals(DualWriteSerializer.FORMAT_JSON, DualWriteSerializer.extractFormat(headers));
    }

    // ---------- DualFormatDeserializer pairing ----------

    @Test
    void deserializerParsesJsonStampedMessage() {
        DualWriteSerializer ser = new DualWriteSerializer();
        ser.configure(new HashMap<>(), false);
        DualFormatDeserializer deser = new DualFormatDeserializer();
        deser.configure(new HashMap<>(), false);

        Headers headers = new RecordHeaders();
        byte[] value = ser.serialize(TOPIC, headers, "{\"event_type\":\"PaymentCaptured\",\"v\":1}");

        Map<String, Object> result = deser.deserialize(TOPIC, headers, value);

        assertNotNull(result);
        assertEquals("PaymentCaptured", result.get("event_type"));
        assertEquals(1, result.get("v"));
    }

    @Test
    void deserializerParsesDualStampedMessageAsJson() {
        DualWriteSerializer ser = new DualWriteSerializer();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(DualWriteSerializer.DUAL_WRITE_ENABLED_CONFIG, "true");
        ser.configure(cfg, false);
        DualFormatDeserializer deser = new DualFormatDeserializer();
        deser.configure(new HashMap<>(), false);

        Headers headers = new RecordHeaders();
        byte[] value = ser.serialize(TOPIC, headers, "{\"amount\":7500}");

        // Header is DUAL but value is JSON, so deserializer reads it as JSON
        assertEquals(DualWriteSerializer.FORMAT_DUAL, headerValue(headers, DualWriteSerializer.HEADER_PAYLOAD_FORMAT));
        Map<String, Object> result = deser.deserialize(TOPIC, headers, value);
        assertEquals(7500, result.get("amount"));
    }

    @Test
    void deserializerNoHeaderTreatedAsJson() {
        DualFormatDeserializer deser = new DualFormatDeserializer();
        deser.configure(new HashMap<>(), false);

        byte[] value = "{\"legacy\":true}".getBytes(StandardCharsets.UTF_8);
        Headers headers = new RecordHeaders(); // no format header

        Map<String, Object> result = deser.deserialize(TOPIC, headers, value);
        assertEquals(true, result.get("legacy"));
    }

    @Test
    void deserializerReturnsNullForNullAndEmptyData() {
        DualFormatDeserializer deser = new DualFormatDeserializer();
        deser.configure(new HashMap<>(), false);
        Headers headers = new RecordHeaders();

        assertNull(deser.deserialize(TOPIC, headers, (byte[]) null));
        assertNull(deser.deserialize(TOPIC, headers, new byte[0]));
    }

    @Test
    void deserializerAvroFormatFallsBackToJsonHeaderWhenNoAvroDeserializer() {
        // AVRO-stamped but no Schema Registry configured -> falls back to HEADER_JSON_PAYLOAD.
        DualFormatDeserializer deser = new DualFormatDeserializer();
        deser.configure(new HashMap<>(), false); // no schema.registry.url -> avroDeserializer is null

        Headers headers = new RecordHeaders();
        DualWriteSerializer.attachJsonHeader(headers, "{\"from\":\"json_header\"}");
        // value bytes are intentionally non-JSON to prove the JSON header is preferred
        byte[] value = "not-valid-json-avro-bytes".getBytes(StandardCharsets.UTF_8);

        Map<String, Object> result = deser.deserialize(TOPIC, headers, value);
        assertEquals("json_header", result.get("from"));
    }

    @Test
    void deserializerAvroFormatFallsBackToValueAsJsonWhenNoJsonHeader() {
        // AVRO-stamped, no Avro deserializer, no JSON header -> last resort parses value as JSON.
        DualFormatDeserializer deser = new DualFormatDeserializer();
        deser.configure(new HashMap<>(), false);

        Headers headers = new RecordHeaders();
        headers.add(new RecordHeader(DualWriteSerializer.HEADER_PAYLOAD_FORMAT,
                DualWriteSerializer.FORMAT_AVRO.getBytes(StandardCharsets.UTF_8)));
        byte[] value = "{\"value_as_json\":42}".getBytes(StandardCharsets.UTF_8);

        Map<String, Object> result = deser.deserialize(TOPIC, headers, value);
        assertEquals(42, result.get("value_as_json"));
    }

    @Test
    void deserializerAvroFormatThrowsWhenNoFallbackSucceeds() {
        // AVRO-stamped, no Avro deserializer, no JSON header, and value is NOT valid JSON ->
        // last-resort JSON parse fails and a RuntimeException is thrown.
        DualFormatDeserializer deser = new DualFormatDeserializer();
        deser.configure(new HashMap<>(), false);

        Headers headers = new RecordHeaders();
        headers.add(new RecordHeader(DualWriteSerializer.HEADER_PAYLOAD_FORMAT,
                DualWriteSerializer.FORMAT_AVRO.getBytes(StandardCharsets.UTF_8)));
        // Plain non-JSON text (no BOM); Jackson cannot parse it as a Map.
        byte[] value = "this is not json at all".getBytes(StandardCharsets.UTF_8);

        assertThrows(RuntimeException.class, () -> deser.deserialize(TOPIC, headers, value));
    }
}
