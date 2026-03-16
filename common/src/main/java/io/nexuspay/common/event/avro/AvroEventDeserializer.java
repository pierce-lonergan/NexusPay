package io.nexuspay.common.event.avro;

import io.nexuspay.common.event.EventEnvelope;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Converts an Avro {@link GenericRecord} back into an {@link EventEnvelope}.
 *
 * <p>Extracts the standard envelope fields and puts all remaining domain-specific
 * fields into the payload map, reproducing the same structure that JSON consumers expect.
 */
public class AvroEventDeserializer {

    private static final Logger log = LoggerFactory.getLogger(AvroEventDeserializer.class);

    /** Fields that belong to the envelope, not the domain payload. */
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "event_id", "event_type", "aggregate_type", "aggregate_id",
            "timestamp", "version", "metadata"
    );

    /**
     * Converts an Avro GenericRecord to an EventEnvelope.
     *
     * @param record the Avro record
     * @return the reconstructed EventEnvelope
     */
    public EventEnvelope deserialize(GenericRecord record) {
        String eventId = getString(record, "event_id");
        String eventType = getString(record, "event_type");
        String aggregateType = getString(record, "aggregate_type");
        String aggregateId = getString(record, "aggregate_id");
        long timestampMillis = (long) record.get("timestamp");
        Instant timestamp = Instant.ofEpochMilli(timestampMillis);
        int version = (int) record.get("version");

        // Extract metadata
        Map<String, String> metadata = extractMetadata(record.get("metadata"));

        // Extract domain payload: all fields not in the envelope set
        Map<String, Object> payload = new HashMap<>();
        for (var field : record.getSchema().getFields()) {
            if (!ENVELOPE_FIELDS.contains(field.name())) {
                Object value = record.get(field.name());
                payload.put(field.name(), unwrapAvroValue(value));
            }
        }

        return new EventEnvelope(eventId, eventType, aggregateType, aggregateId,
                timestamp, version, metadata, payload);
    }

    private Map<String, String> extractMetadata(Object metadataObj) {
        Map<String, String> metadata = new HashMap<>();
        if (metadataObj instanceof GenericRecord metaRecord) {
            for (var field : metaRecord.getSchema().getFields()) {
                Object value = metaRecord.get(field.name());
                if (value != null) {
                    metadata.put(field.name(), value.toString());
                }
            }
        }
        return metadata;
    }

    /**
     * Unwraps Avro-specific types (CharSequence, GenericRecord) to plain Java types
     * that match the JSON-deserialized structure consumers expect.
     */
    @SuppressWarnings("unchecked")
    private Object unwrapAvroValue(Object value) {
        if (value == null) return null;
        if (value instanceof CharSequence cs) return cs.toString();
        if (value instanceof GenericRecord nested) {
            Map<String, Object> map = new HashMap<>();
            for (var field : nested.getSchema().getFields()) {
                map.put(field.name(), unwrapAvroValue(nested.get(field.name())));
            }
            return map;
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(this::unwrapAvroValue).toList();
        }
        if (value instanceof org.apache.avro.generic.GenericEnumSymbol<?> enumSymbol) {
            return enumSymbol.toString();
        }
        return value;
    }

    private String getString(GenericRecord record, String fieldName) {
        Object value = record.get(fieldName);
        return value != null ? value.toString() : null;
    }
}
