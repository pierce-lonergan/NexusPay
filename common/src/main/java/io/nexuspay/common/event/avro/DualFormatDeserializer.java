package io.nexuspay.common.event.avro;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Kafka {@link Deserializer} that auto-detects the payload format (JSON or Avro)
 * and returns a {@code Map<String, Object>} matching the existing {@link io.nexuspay.common.event.EventEnvelope}
 * structure that all consumers expect.
 *
 * <p>Format detection strategy:
 * <ol>
 *   <li>Check {@code nexuspay_payload_format} header:
 *       <ul>
 *         <li>{@code JSON} or absent → deserialize value as JSON</li>
 *         <li>{@code AVRO} → deserialize value as Avro GenericRecord, convert to Map</li>
 *         <li>{@code DUAL} → value is JSON (dual-write phase), deserialize as JSON</li>
 *       </ul>
 *   </li>
 *   <li>Fallback: if header is missing, attempt JSON deserialization (backward compatible)</li>
 * </ol>
 *
 * <p>This single consumer factory change makes all existing consumers dual-format compatible
 * without touching their handler code.
 */
public class DualFormatDeserializer implements Deserializer<Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(DualFormatDeserializer.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GenericRecordToMapConverter avroToMapConverter = new GenericRecordToMapConverter();

    // These will be set via configure() when Schema Registry is available
    private Deserializer<GenericRecord> avroDeserializer;

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // Try to initialize the Avro deserializer if Schema Registry URL is configured
        String srUrl = configs != null ? (String) configs.get("schema.registry.url") : null;
        if (srUrl != null && !srUrl.isBlank()) {
            try {
                var avroDeser = new io.confluent.kafka.serializers.KafkaAvroDeserializer();
                avroDeser.configure(configs, isKey);
                this.avroDeserializer = (topic, data) -> (GenericRecord) avroDeser.deserialize(topic, data);
                log.info("DualFormatDeserializer: Avro deserializer initialized with SR at {}", srUrl);
            } catch (Exception e) {
                log.warn("DualFormatDeserializer: Failed to initialize Avro deserializer, Avro messages " +
                         "will fall back to JSON header. Error: {}", e.getMessage());
            }
        } else {
            log.info("DualFormatDeserializer: No Schema Registry URL configured, JSON-only mode");
        }
    }

    @Override
    public Map<String, Object> deserialize(String topic, byte[] data) {
        // Without headers, assume JSON
        return deserializeJson(data);
    }

    @Override
    public Map<String, Object> deserialize(String topic, Headers headers, byte[] data) {
        if (data == null || data.length == 0) return null;

        String format = extractFormat(headers);

        return switch (format) {
            case DualWriteSerializer.FORMAT_AVRO -> deserializeAvro(topic, headers, data);
            case DualWriteSerializer.FORMAT_DUAL -> deserializeJson(data); // Dual-write: value is JSON
            default -> deserializeJson(data); // JSON or unknown
        };
    }

    private Map<String, Object> deserializeAvro(String topic, Headers headers, byte[] data) {
        // First try Avro deserialization via Schema Registry
        if (avroDeserializer != null) {
            try {
                GenericRecord record = avroDeserializer.deserialize(topic, data);
                if (record != null) {
                    return avroToMapConverter.convert(record);
                }
            } catch (Exception e) {
                log.warn("Avro deserialization failed for topic '{}', trying JSON header fallback: {}",
                        topic, e.getMessage());
            }
        }

        // Fallback: check for JSON payload in header (dual-write backward compatibility)
        Header jsonHeader = headers.lastHeader(DualWriteSerializer.HEADER_JSON_PAYLOAD);
        if (jsonHeader != null) {
            return deserializeJson(jsonHeader.value());
        }

        // Last resort: try parsing value as JSON (it might be JSON with wrong format header)
        try {
            return deserializeJson(data);
        } catch (Exception e) {
            log.error("Failed to deserialize message from topic '{}' in any format", topic, e);
            throw new RuntimeException("Unable to deserialize message from topic: " + topic, e);
        }
    }

    private Map<String, Object> deserializeJson(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            return objectMapper.readValue(data, MAP_TYPE);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize JSON payload", e);
        }
    }

    private String extractFormat(Headers headers) {
        if (headers == null) return DualWriteSerializer.FORMAT_JSON;
        Header header = headers.lastHeader(DualWriteSerializer.HEADER_PAYLOAD_FORMAT);
        if (header == null) return DualWriteSerializer.FORMAT_JSON;
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        // No resources to release
    }
}
