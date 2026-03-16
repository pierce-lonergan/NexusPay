package io.nexuspay.common.event.avro;

import io.nexuspay.common.event.EventEnvelope;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Converts an {@link EventEnvelope} into an Avro {@link GenericRecord}.
 *
 * <p>The envelope's flat fields (event_id, event_type, timestamp, etc.) and
 * nested payload fields are merged into a single map that the {@link JsonToAvroConverter}
 * maps onto the Avro schema's field structure.
 *
 * <p>This class does NOT handle Kafka serialization (byte encoding). That responsibility
 * belongs to the Confluent {@code KafkaAvroSerializer} configured in the producer.
 */
public class AvroEventSerializer {

    private static final Logger log = LoggerFactory.getLogger(AvroEventSerializer.class);

    private final JsonToAvroConverter converter = new JsonToAvroConverter();

    /**
     * Serializes an EventEnvelope to an Avro GenericRecord.
     *
     * @param envelope the event envelope to serialize
     * @return the Avro record, or empty if the event type has no Avro schema mapping
     * @throws AvroConversionException if conversion fails
     */
    public Optional<GenericRecord> serialize(EventEnvelope envelope) {
        Optional<Schema> schemaOpt = EventSchemaMapping.schemaFor(envelope.event_type());
        if (schemaOpt.isEmpty()) {
            log.debug("No Avro schema for event type '{}', skipping Avro serialization", envelope.event_type());
            return Optional.empty();
        }

        Schema schema = schemaOpt.get();

        // Merge envelope fields and payload into a single flat map for conversion.
        // Avro schemas define all fields at the top level (envelope + domain-specific).
        Map<String, Object> flatMap = new HashMap<>();

        // Envelope fields
        flatMap.put("event_id", envelope.event_id());
        flatMap.put("event_type", envelope.event_type());
        flatMap.put("aggregate_type", envelope.aggregate_type());
        flatMap.put("aggregate_id", envelope.aggregate_id());
        flatMap.put("timestamp", envelope.timestamp());
        flatMap.put("version", envelope.version());

        // Metadata as nested record
        if (envelope.metadata() != null) {
            flatMap.put("metadata", envelope.metadata());
        }

        // Domain-specific payload fields merged at top level
        if (envelope.payload() != null) {
            flatMap.putAll(envelope.payload());
        }

        GenericRecord record = converter.convert(flatMap, schema);
        return Optional.of(record);
    }
}
