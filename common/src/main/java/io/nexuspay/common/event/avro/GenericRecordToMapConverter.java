package io.nexuspay.common.event.avro;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericEnumSymbol;
import org.apache.avro.generic.GenericRecord;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts an Avro {@link GenericRecord} to a {@code Map<String, Object>} matching
 * the structure that existing JSON consumers (using {@code JsonDeserializer}) expect.
 *
 * <p>Handles:
 * <ul>
 *   <li>Unions (nullable fields): unwraps to value or null</li>
 *   <li>Nested records (EventMetadata, Money): converts to nested Map</li>
 *   <li>Logical types (timestamp-millis): converts long to ISO-8601 string</li>
 *   <li>Enums: converts to string</li>
 *   <li>CharSequence (Avro Utf8): converts to String</li>
 *   <li>Arrays: recursively converts elements</li>
 * </ul>
 */
public class GenericRecordToMapConverter {

    /**
     * Converts a GenericRecord to a Map matching the JSON-deserialized structure.
     *
     * @param record the Avro record to convert
     * @return a Map suitable for consumption by existing JSON-based event handlers
     */
    public Map<String, Object> convert(GenericRecord record) {
        Map<String, Object> map = new HashMap<>();
        for (Schema.Field field : record.getSchema().getFields()) {
            Object value = record.get(field.name());
            map.put(field.name(), unwrap(value, field.schema()));
        }
        return map;
    }

    private Object unwrap(Object value, Schema schema) {
        if (value == null) return null;

        // Unwrap union — find the actual type
        if (schema.getType() == Schema.Type.UNION) {
            Schema actualSchema = resolveUnionSchema(value, schema);
            return unwrap(value, actualSchema);
        }

        return switch (schema.getType()) {
            case STRING -> value.toString(); // Avro Utf8 → String
            case LONG -> unwrapLong(value, schema);
            case INT, FLOAT, DOUBLE, BOOLEAN -> value;
            case RECORD -> {
                if (value instanceof GenericRecord nested) {
                    yield convert(nested);
                }
                yield value;
            }
            case ARRAY -> {
                if (value instanceof List<?> list) {
                    Schema elementSchema = schema.getElementType();
                    yield list.stream().map(item -> unwrap(item, elementSchema)).toList();
                }
                yield value;
            }
            case ENUM -> {
                if (value instanceof GenericEnumSymbol<?> enumSymbol) {
                    yield enumSymbol.toString();
                }
                yield value.toString();
            }
            case BYTES -> value;
            case NULL -> null;
            default -> value;
        };
    }

    /**
     * Handles long values with logical types.
     * timestamp-millis → ISO-8601 string for JSON compatibility.
     */
    private Object unwrapLong(Object value, Schema schema) {
        String logicalType = schema.getProp("logicalType");
        if ("timestamp-millis".equals(logicalType) && value instanceof Long millis) {
            return Instant.ofEpochMilli(millis).toString();
        }
        return value;
    }

    /**
     * Resolves which branch of a union schema the value belongs to.
     */
    private Schema resolveUnionSchema(Object value, Schema unionSchema) {
        for (Schema branch : unionSchema.getTypes()) {
            if (branch.getType() == Schema.Type.NULL) continue;
            // For most cases, the non-null branch is what we want
            return branch;
        }
        return unionSchema.getTypes().get(0);
    }
}
