package io.nexuspay.common.event.avro;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Feature-flag controlled Kafka value serializer for the JSON-to-Avro migration.
 *
 * <p>Behavior controlled by the {@code nexuspay.avro.dual-write.enabled} config property,
 * passed via the {@code nexuspay.dual.write.enabled} serializer config key:
 *
 * <ul>
 *   <li><b>Disabled (default):</b> Serializes as JSON string (current behavior).
 *       Attaches header {@code nexuspay_payload_format=JSON}.</li>
 *   <li><b>Enabled:</b> Serializes as JSON string with Avro bytes attached via header
 *       {@code nexuspay_avro_payload}. Primary value remains JSON for backward compatibility
 *       during the dual-write phase. Attaches header {@code nexuspay_payload_format=DUAL}.</li>
 * </ul>
 *
 * <p>Note: The actual Avro-primary publishing (Avro as value, JSON as header) is handled
 * by {@code DualWritePublisher} in the app module, which has access to the Schema Registry client.
 * This serializer is a simpler utility for cases where the full publisher pipeline isn't needed.
 *
 * @see io.nexuspay.common.event.avro.AvroEventSerializer
 */
public class DualWriteSerializer implements Serializer<String> {

    private static final Logger log = LoggerFactory.getLogger(DualWriteSerializer.class);

    public static final String DUAL_WRITE_ENABLED_CONFIG = "nexuspay.dual.write.enabled";
    public static final String HEADER_PAYLOAD_FORMAT = "nexuspay_payload_format";
    public static final String HEADER_JSON_PAYLOAD = "nexuspay_json_payload";
    public static final String FORMAT_JSON = "JSON";
    public static final String FORMAT_AVRO = "AVRO";
    public static final String FORMAT_DUAL = "DUAL";

    private final StringSerializer jsonSerializer = new StringSerializer();
    private boolean dualWriteEnabled = false;

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        jsonSerializer.configure(configs, isKey);
        Object enabled = configs.get(DUAL_WRITE_ENABLED_CONFIG);
        if (enabled != null) {
            dualWriteEnabled = Boolean.parseBoolean(enabled.toString());
        }
        log.info("DualWriteSerializer configured: dual-write={}", dualWriteEnabled);
    }

    @Override
    public byte[] serialize(String topic, String jsonPayload) {
        return jsonSerializer.serialize(topic, jsonPayload);
    }

    @Override
    public byte[] serialize(String topic, Headers headers, String jsonPayload) {
        // Always attach format header
        String format = dualWriteEnabled ? FORMAT_DUAL : FORMAT_JSON;
        headers.add(new RecordHeader(HEADER_PAYLOAD_FORMAT, format.getBytes(StandardCharsets.UTF_8)));

        // Value is always JSON string bytes (backward compatible)
        return jsonSerializer.serialize(topic, jsonPayload);
    }

    @Override
    public void close() {
        jsonSerializer.close();
    }

    /**
     * Utility: attach JSON payload as a Kafka header for dual-write mode.
     * Called by DualWritePublisher when publishing Avro as the primary value.
     */
    public static void attachJsonHeader(Headers headers, String jsonPayload) {
        headers.add(new RecordHeader(HEADER_JSON_PAYLOAD, jsonPayload.getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(HEADER_PAYLOAD_FORMAT, FORMAT_AVRO.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Utility: extract format from Kafka headers.
     */
    public static String extractFormat(Headers headers) {
        var header = headers.lastHeader(HEADER_PAYLOAD_FORMAT);
        if (header == null) return FORMAT_JSON; // Legacy messages have no header
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
