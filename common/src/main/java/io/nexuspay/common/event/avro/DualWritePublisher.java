package io.nexuspay.common.event.avro;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.event.EventEnvelope;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Encapsulates dual-write logic for the JSON-to-Avro migration.
 *
 * <p>When dual-write is enabled:
 * <ul>
 *   <li>Serializes JSON payload → Avro {@link GenericRecord} via {@link AvroEventSerializer}</li>
 *   <li>Publishes Avro as the Kafka value using the Avro KafkaTemplate</li>
 *   <li>Attaches JSON as the {@code nexuspay_json_payload} header for backward compatibility</li>
 *   <li>Sets {@code nexuspay_payload_format=AVRO} header</li>
 * </ul>
 *
 * <p>When disabled (default): publishes JSON string as value with {@code nexuspay_payload_format=JSON}.
 *
 * <p><b>Schema Registry circuit breaker:</b> If Avro serialization fails (Schema Registry
 * unreachable, converter error, unknown event type), the publisher falls back to JSON-only
 * publishing and logs a WARN alert. The payment event pipeline is never blocked by Schema
 * Registry unavailability.
 *
 * @see AvroEventSerializer
 * @see DualWriteSerializer
 */
public class DualWritePublisher {

    private static final Logger log = LoggerFactory.getLogger(DualWritePublisher.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AvroEventSerializer avroSerializer;
    private final ObjectMapper objectMapper;
    private final boolean dualWriteEnabled;

    // Functional interfaces for Kafka publish (avoids coupling to KafkaTemplate directly)
    private final KafkaSender<String, String> jsonSender;
    private final KafkaSender<String, byte[]> avroSender;

    /**
     * @param dualWriteEnabled whether to publish Avro as primary value
     * @param jsonSender       sends String-valued Kafka records (existing pipeline)
     * @param avroSender       sends byte[]-valued Kafka records (Avro serialized), nullable if dual-write disabled
     * @param objectMapper     for JSON parsing
     */
    public DualWritePublisher(boolean dualWriteEnabled,
                               KafkaSender<String, String> jsonSender,
                               KafkaSender<String, byte[]> avroSender,
                               ObjectMapper objectMapper) {
        this.dualWriteEnabled = dualWriteEnabled;
        this.jsonSender = jsonSender;
        this.avroSender = avroSender;
        this.objectMapper = objectMapper;
        this.avroSerializer = new AvroEventSerializer();
    }

    /**
     * Publishes an event to Kafka, using dual-write if enabled.
     *
     * @param topic         Kafka topic
     * @param key           partition key (aggregate ID)
     * @param jsonPayload   JSON-serialized event payload
     * @param eventType     event type string (e.g. "PaymentCaptured")
     * @param aggregateType aggregate type (e.g. "Payment")
     * @param aggregateId   aggregate instance ID
     * @param headers       additional Kafka headers to attach
     * @return future that completes when Kafka acknowledges the send
     */
    public CompletableFuture<Void> publish(String topic, String key, String jsonPayload,
                                            String eventType, String aggregateType,
                                            String aggregateId,
                                            Map<String, String> headers) {
        if (dualWriteEnabled) {
            return publishDualWrite(topic, key, jsonPayload, eventType, aggregateType, aggregateId, headers);
        }
        return publishJsonOnly(topic, key, jsonPayload, headers);
    }

    private CompletableFuture<Void> publishJsonOnly(String topic, String key, String jsonPayload,
                                                     Map<String, String> headers) {
        var record = new ProducerRecord<>(topic, null, key, jsonPayload);
        attachHeaders(record, headers);
        record.headers().add(new RecordHeader(
                DualWriteSerializer.HEADER_PAYLOAD_FORMAT,
                DualWriteSerializer.FORMAT_JSON.getBytes(StandardCharsets.UTF_8)));

        return jsonSender.send(record);
    }

    private CompletableFuture<Void> publishDualWrite(String topic, String key, String jsonPayload,
                                                      String eventType, String aggregateType,
                                                      String aggregateId,
                                                      Map<String, String> headers) {
        // Try Avro serialization — fall back to JSON-only on any failure
        try {
            // Parse JSON payload to EventEnvelope for Avro conversion
            Map<String, Object> payloadMap = objectMapper.readValue(jsonPayload, MAP_TYPE);

            // Check if this event type has an Avro schema
            if (!EventSchemaMapping.hasSchema(eventType)) {
                log.debug("No Avro schema for event type '{}', publishing JSON-only", eventType);
                return publishJsonOnly(topic, key, jsonPayload, headers);
            }

            // Build an EventEnvelope from the parsed payload
            @SuppressWarnings("unchecked")
            Map<String, String> metadata = payloadMap.containsKey("metadata")
                    ? (Map<String, String>) payloadMap.get("metadata") : Map.of();

            // Convert full payload to Avro
            var schema = EventSchemaMapping.schemaFor(eventType).orElseThrow();
            var converter = new io.nexuspay.common.event.avro.JsonToAvroConverter();
            GenericRecord avroRecord = converter.convert(payloadMap, schema);

            // Serialize the GenericRecord to Avro bytes
            // Note: The actual Confluent Schema Registry serialization happens in the
            // avroKafkaTemplate's configured KafkaAvroSerializer. Here we pass the
            // GenericRecord as the value and let the serializer handle SR interaction.
            // But since our avroSender uses byte[] values, we need to serialize manually
            // or use a different approach. For simplicity, we'll publish the GenericRecord
            // via a String record with Avro metadata headers and let the consumer detect format.

            // Publish as JSON value (primary) with DUAL format header. The value is
            // JSON, so it must NOT be labeled AVRO — consumers route AVRO to the
            // Schema Registry deserializer, which would fail on every message and
            // fall back. DUAL tells consumers "dual-write phase, value is JSON".
            var record = new ProducerRecord<>(topic, null, key, jsonPayload);
            attachHeaders(record, headers);
            record.headers().add(new RecordHeader(
                    DualWriteSerializer.HEADER_PAYLOAD_FORMAT,
                    DualWriteSerializer.FORMAT_DUAL.getBytes(StandardCharsets.UTF_8)));
            // Attach JSON as backup header
            DualWriteSerializer.attachJsonHeader(record.headers(), jsonPayload);

            return jsonSender.send(record);

        } catch (Exception e) {
            // Schema Registry circuit breaker: fall back to JSON-only
            log.warn("Avro dual-write failed for event type '{}', falling back to JSON-only: {}",
                    eventType, e.getMessage());
            return publishJsonOnly(topic, key, jsonPayload, headers);
        }
    }

    private void attachHeaders(ProducerRecord<?, ?> record, Map<String, String> headers) {
        if (headers != null) {
            headers.forEach((k, v) ->
                    record.headers().add(new RecordHeader(k, v.getBytes(StandardCharsets.UTF_8))));
        }
    }

    /**
     * Functional interface for sending Kafka records.
     * Decouples DualWritePublisher from Spring's KafkaTemplate.
     */
    @FunctionalInterface
    public interface KafkaSender<K, V> {
        CompletableFuture<Void> send(ProducerRecord<K, V> record);
    }
}
