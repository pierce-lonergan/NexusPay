package io.nexuspay.common.event.avro;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Converts a JSON-sourced {@code Map<String, Object>} payload into an Avro {@link GenericRecord}
 * using the target schema for the event type.
 *
 * <p>Handles type coercion required by the Avro type system:
 * <ul>
 *   <li>String/Number → long (for timestamp-millis, Money.amount)</li>
 *   <li>Nested Map → nested GenericRecord (EventMetadata, Money)</li>
 *   <li>Null values → Avro union null branches</li>
 *   <li>List values → Avro array</li>
 * </ul>
 */
public class JsonToAvroConverter {

    private static final Logger log = LoggerFactory.getLogger(JsonToAvroConverter.class);

    /**
     * Converts a flat JSON map to an Avro GenericRecord using the given schema.
     *
     * @param jsonMap the JSON payload as a Map (from Jackson deserialization)
     * @param schema  the target Avro schema
     * @return a populated GenericRecord
     * @throws AvroConversionException if conversion fails due to missing required fields or type mismatch
     */
    public GenericRecord convert(Map<String, Object> jsonMap, Schema schema) {
        try {
            GenericRecord record = new GenericData.Record(schema);
            for (Schema.Field field : schema.getFields()) {
                Object value = jsonMap.get(field.name());
                record.put(field.name(), coerce(value, field.schema(), field.name()));
            }
            return record;
        } catch (AvroConversionException e) {
            throw e;
        } catch (Exception e) {
            throw new AvroConversionException(
                    "Failed to convert JSON to Avro for schema: " + schema.getFullName(), e);
        }
    }

    /**
     * Coerces a JSON-sourced value to match the target Avro schema type.
     */
    @SuppressWarnings("unchecked")
    private Object coerce(Object value, Schema schema, String fieldName) {
        // Unwrap union types: ["null", "type"] or ["type", "null"]
        if (schema.getType() == Schema.Type.UNION) {
            List<Schema> types = schema.getTypes();
            if (value == null) {
                // Verify null is a valid branch
                boolean hasNull = types.stream().anyMatch(s -> s.getType() == Schema.Type.NULL);
                if (hasNull) return null;
                throw new AvroConversionException("Null value for non-nullable union field: " + fieldName);
            }
            // Find the non-null branch and coerce to it
            Schema nonNullSchema = types.stream()
                    .filter(s -> s.getType() != Schema.Type.NULL)
                    .findFirst()
                    .orElseThrow(() -> new AvroConversionException("Union with only null type for field: " + fieldName));
            return coerce(value, nonNullSchema, fieldName);
        }

        if (value == null) {
            if (schema.getType() == Schema.Type.NULL) return null;
            // Check for default
            throw new AvroConversionException("Null value for non-nullable field: " + fieldName);
        }

        return switch (schema.getType()) {
            case STRING -> String.valueOf(value);
            case INT -> coerceToInt(value, fieldName);
            case LONG -> coerceToLong(value, fieldName);
            case FLOAT -> coerceToFloat(value, fieldName);
            case DOUBLE -> coerceToDouble(value, fieldName);
            case BOOLEAN -> coerceToBoolean(value, fieldName);
            case RECORD -> coerceToRecord(value, schema, fieldName);
            case ARRAY -> coerceToArray(value, schema, fieldName);
            case ENUM -> new GenericData.EnumSymbol(schema, String.valueOf(value));
            case NULL -> null;
            case BYTES -> value instanceof byte[] bytes ? bytes : String.valueOf(value).getBytes();
            default -> value;
        };
    }

    private int coerceToInt(Object value, String fieldName) {
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new AvroConversionException("Cannot coerce '" + value + "' to int for field: " + fieldName, e);
        }
    }

    private long coerceToLong(Object value, String fieldName) {
        // Handle Instant/timestamp strings for timestamp-millis logical type
        if (value instanceof Instant instant) return instant.toEpochMilli();
        if (value instanceof String s) {
            try {
                return Instant.parse(s).toEpochMilli();
            } catch (Exception ignored) {
                // Fall through to numeric parsing
            }
        }
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new AvroConversionException("Cannot coerce '" + value + "' to long for field: " + fieldName, e);
        }
    }

    private float coerceToFloat(Object value, String fieldName) {
        if (value instanceof Number n) return n.floatValue();
        try {
            return Float.parseFloat(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new AvroConversionException("Cannot coerce '" + value + "' to float for field: " + fieldName, e);
        }
    }

    private double coerceToDouble(Object value, String fieldName) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new AvroConversionException("Cannot coerce '" + value + "' to double for field: " + fieldName, e);
        }
    }

    private boolean coerceToBoolean(Object value, String fieldName) {
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private GenericRecord coerceToRecord(Object value, Schema schema, String fieldName) {
        if (value instanceof Map<?, ?> map) {
            return convert((Map<String, Object>) map, schema);
        }
        throw new AvroConversionException(
                "Expected Map for record field '" + fieldName + "', got: " + value.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private List<Object> coerceToArray(Object value, Schema schema, String fieldName) {
        if (value instanceof List<?> list) {
            Schema elementSchema = schema.getElementType();
            return list.stream()
                    .map(item -> coerce(item, elementSchema, fieldName + "[]"))
                    .toList();
        }
        throw new AvroConversionException(
                "Expected List for array field '" + fieldName + "', got: " + value.getClass().getSimpleName());
    }
}
