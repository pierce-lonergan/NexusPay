package io.nexuspay.common.event.avro;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JsonToAvroConverter}.
 *
 * <p>Exercises the type-coercion paths that protect the integrity of financial events
 * as they cross from JSON into Avro before hitting the event log: Money.amount as long,
 * timestamp coercion, union unwrapping, nested records, arrays, and the error paths.
 */
class JsonToAvroConverterTest {

    private final JsonToAvroConverter converter = new JsonToAvroConverter();

    // ---------- LONG coercion (Money.amount + timestamps) ----------

    @Test
    void coercesPlainNumericStringToLong() {
        Schema schema = SchemaBuilder.record("Amount").fields()
                .name("amount").type().longType().noDefault()
                .endRecord();

        // coerceToLong tries Instant.parse FIRST, then numeric. A plain numeric string
        // must still fall through to Long.parseLong and NOT throw.
        GenericRecord record = converter.convert(Map.of("amount", "1000"), schema);

        assertEquals(1000L, record.get("amount"));
    }

    @Test
    void coercesNumberToLong() {
        Schema schema = SchemaBuilder.record("Amount").fields()
                .name("amount").type().longType().noDefault()
                .endRecord();

        GenericRecord record = converter.convert(Map.of("amount", 4250), schema);

        assertEquals(4250L, record.get("amount"));
    }

    @Test
    void coercesIso8601TimestampStringToEpochMillis() {
        Schema schema = SchemaBuilder.record("Evt").fields()
                .name("ts").type().longType().noDefault()
                .endRecord();

        GenericRecord record = converter.convert(Map.of("ts", "2026-03-15T12:00:00Z"), schema);

        long expected = Instant.parse("2026-03-15T12:00:00Z").toEpochMilli();
        assertEquals(expected, record.get("ts"));
        // Sanity: it is the epoch-milli, not a parse failure or the raw string
        assertNotEquals(0L, record.get("ts"));
    }

    @Test
    void coercesInstantInstanceToEpochMillis() {
        Schema schema = SchemaBuilder.record("Evt").fields()
                .name("ts").type().longType().noDefault()
                .endRecord();

        Instant now = Instant.parse("2026-01-02T03:04:05Z");
        Map<String, Object> map = new HashMap<>();
        map.put("ts", now);

        GenericRecord record = converter.convert(map, schema);

        assertEquals(now.toEpochMilli(), record.get("ts"));
    }

    @Test
    void nonNumericNonTimestampStringToLongThrowsWithFieldName() {
        Schema schema = SchemaBuilder.record("Amount").fields()
                .name("amount").type().longType().noDefault()
                .endRecord();

        AvroConversionException ex = assertThrows(AvroConversionException.class,
                () -> converter.convert(Map.of("amount", "not-a-number"), schema));

        assertTrue(ex.getMessage().contains("amount"),
                "error message should name the offending field, was: " + ex.getMessage());
    }

    // ---------- INT / FLOAT / DOUBLE / BOOLEAN ----------

    @Test
    void coercesIntHappyAndInvalid() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("n").type().intType().noDefault()
                .endRecord();

        assertEquals(42, converter.convert(Map.of("n", "42"), schema).get("n"));
        assertEquals(7, converter.convert(Map.of("n", 7L), schema).get("n")); // narrows Number

        AvroConversionException ex = assertThrows(AvroConversionException.class,
                () -> converter.convert(Map.of("n", "x"), schema));
        assertTrue(ex.getMessage().contains("n"));
        assertTrue(ex.getMessage().contains("int"));
    }

    @Test
    void coercesFloatHappyAndInvalid() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("f").type().floatType().noDefault()
                .endRecord();

        assertEquals(1.5f, converter.convert(Map.of("f", "1.5"), schema).get("f"));

        AvroConversionException ex = assertThrows(AvroConversionException.class,
                () -> converter.convert(Map.of("f", "abc"), schema));
        assertTrue(ex.getMessage().contains("f"));
        assertTrue(ex.getMessage().contains("float"));
    }

    @Test
    void coercesDoubleHappyAndInvalid() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("d").type().doubleType().noDefault()
                .endRecord();

        assertEquals(2.25d, converter.convert(Map.of("d", "2.25"), schema).get("d"));

        AvroConversionException ex = assertThrows(AvroConversionException.class,
                () -> converter.convert(Map.of("d", "nope"), schema));
        assertTrue(ex.getMessage().contains("d"));
        assertTrue(ex.getMessage().contains("double"));
    }

    @Test
    void coercesBooleanFromStringAndBoolean() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("b").type().booleanType().noDefault()
                .endRecord();

        assertEquals(true, converter.convert(Map.of("b", true), schema).get("b"));
        assertEquals(true, converter.convert(Map.of("b", "true"), schema).get("b"));
        // Boolean.parseBoolean is lenient: any non-"true" string is false (documented behavior)
        assertEquals(false, converter.convert(Map.of("b", "yes"), schema).get("b"));
        assertEquals(false, converter.convert(Map.of("b", "false"), schema).get("b"));
    }

    @Test
    void coercesStringFromNonStringValue() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("s").type().stringType().noDefault()
                .endRecord();

        // STRING branch uses String.valueOf — a numeric value becomes its string form
        assertEquals("123", converter.convert(Map.of("s", 123), schema).get("s"));
    }

    // ---------- UNION ----------

    @Test
    void nullOnNullableUnionReturnsNull() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("opt").type().unionOf().nullType().and().longType().endUnion().nullDefault()
                .endRecord();

        Map<String, Object> map = new HashMap<>();
        map.put("opt", null);

        GenericRecord record = converter.convert(map, schema);
        assertNull(record.get("opt"));
    }

    @Test
    void valueOnNullableUnionCoercesToNonNullBranch() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("opt").type().unionOf().nullType().and().longType().endUnion().nullDefault()
                .endRecord();

        GenericRecord record = converter.convert(Map.of("opt", "55"), schema);
        assertEquals(55L, record.get("opt"));
    }

    @Test
    void nullOnNonNullableUnionThrows() {
        // Union with no null branch: ["long","string"]
        Schema schema = SchemaBuilder.record("R").fields()
                .name("u").type().unionOf().longType().and().stringType().endUnion().noDefault()
                .endRecord();

        Map<String, Object> map = new HashMap<>();
        map.put("u", null);

        AvroConversionException ex = assertThrows(AvroConversionException.class,
                () -> converter.convert(map, schema));
        assertTrue(ex.getMessage().contains("Null value for non-nullable union field"));
        assertTrue(ex.getMessage().contains("u"));
    }

    @Test
    void unionWithOnlyNullBranchAndNonNullValueThrows() {
        // A union whose only branch is null, given a non-null value, hits the
        // "Union with only null type" orElseThrow path.
        Schema onlyNull = Schema.createUnion(List.of(Schema.create(Schema.Type.NULL)));
        Schema schema = Schema.createRecord("R", null, null, false);
        schema.setFields(List.of(new Schema.Field("u", onlyNull, null, (Object) null)));

        AvroConversionException ex = assertThrows(AvroConversionException.class,
                () -> converter.convert(Map.of("u", "x"), schema));
        assertTrue(ex.getMessage().contains("Union with only null type"));
        assertTrue(ex.getMessage().contains("u"));
    }

    // ---------- RECORD (nested) ----------

    @Test
    void coercesNestedRecordFromMap() {
        Schema money = SchemaBuilder.record("Money").fields()
                .name("amount").type().longType().noDefault()
                .name("currency").type().stringType().noDefault()
                .endRecord();
        Schema schema = SchemaBuilder.record("Payment").fields()
                .name("money").type(money).noDefault()
                .endRecord();

        GenericRecord record = converter.convert(
                Map.of("money", Map.of("amount", "2599", "currency", "USD")), schema);

        Object nested = record.get("money");
        assertInstanceOf(GenericRecord.class, nested);
        GenericRecord moneyRecord = (GenericRecord) nested;
        assertEquals(2599L, moneyRecord.get("amount"));
        assertEquals("USD", moneyRecord.get("currency"));
    }

    @Test
    void recordFieldGivenNonMapThrows() {
        Schema money = SchemaBuilder.record("Money").fields()
                .name("amount").type().longType().noDefault()
                .endRecord();
        Schema schema = SchemaBuilder.record("Payment").fields()
                .name("money").type(money).noDefault()
                .endRecord();

        AvroConversionException ex = assertThrows(AvroConversionException.class,
                () -> converter.convert(Map.of("money", "not-a-map"), schema));
        assertTrue(ex.getMessage().contains("Expected Map for record field"));
        assertTrue(ex.getMessage().contains("money"));
    }

    // ---------- ARRAY ----------

    @Test
    void coercesArrayElementByElement() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("nums").type().array().items().longType().noDefault()
                .endRecord();

        GenericRecord record = converter.convert(
                Map.of("nums", List.of("1", "2", "3")), schema);

        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) record.get("nums");
        assertEquals(List.of(1L, 2L, 3L), result);
    }

    @Test
    void arrayFieldGivenNonListThrows() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("nums").type().array().items().longType().noDefault()
                .endRecord();

        AvroConversionException ex = assertThrows(AvroConversionException.class,
                () -> converter.convert(Map.of("nums", "1,2,3"), schema));
        assertTrue(ex.getMessage().contains("Expected List for array field"));
        assertTrue(ex.getMessage().contains("nums"));
    }

    // ---------- missing required field ----------

    @Test
    void missingRequiredFieldThrows() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("required").type().longType().noDefault()
                .endRecord();

        // jsonMap lacks "required" -> value is null on a non-nullable field
        AvroConversionException ex = assertThrows(AvroConversionException.class,
                () -> converter.convert(new HashMap<>(), schema));
        assertTrue(ex.getMessage().contains("Null value for non-nullable field"));
        assertTrue(ex.getMessage().contains("required"));
    }

    // ---------- BYTES (charset bug-watch) ----------

    @Test
    void coercesBytesFromByteArrayPassesThrough() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("data").type().bytesType().noDefault()
                .endRecord();

        byte[] raw = {1, 2, 3};
        GenericRecord record = converter.convert(Map.of("data", raw), schema);
        assertArrayEquals(raw, (byte[]) record.get("data"));
    }

    @Test
    void coercesBytesFromAsciiStringIsDeterministic() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("data").type().bytesType().noDefault()
                .endRecord();

        // ASCII content is charset-independent, so the production code's
        // platform-default getBytes() is safe here.
        GenericRecord record = converter.convert(Map.of("data", "abc"), schema);
        assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), (byte[]) record.get("data"));
    }

    @Test
    void bytesFromNonAsciiStringUsesPlatformDefaultCharset() {
        // BUG-WATCH: BYTES branch is `String.valueOf(value).getBytes()` with no charset.
        // For non-ASCII content the bytes are platform/locale-dependent and may NOT be UTF-8.
        // This test documents the current behavior (matches the JVM default charset).
        Schema schema = SchemaBuilder.record("R").fields()
                .name("data").type().bytesType().noDefault()
                .endRecord();

        String nonAscii = "é€"; // é €
        GenericRecord record = converter.convert(Map.of("data", nonAscii), schema);

        byte[] actual = (byte[]) record.get("data");
        // Asserts it matches the platform default — NOT a guaranteed UTF-8 encoding.
        assertArrayEquals(nonAscii.getBytes(), actual);
    }

    // ---------- ENUM ----------

    @Test
    void coercesEnumKnownSymbol() {
        Schema enumSchema = SchemaBuilder.enumeration("Status").symbols("PENDING", "CAPTURED");
        Schema schema = SchemaBuilder.record("R").fields()
                .name("status").type(enumSchema).noDefault()
                .endRecord();

        GenericRecord record = converter.convert(Map.of("status", "CAPTURED"), schema);
        assertEquals("CAPTURED", record.get("status").toString());
    }

    @Test
    void enumWithSymbolNotInSchemaIsAcceptedSilently() {
        // BUG-WATCH: case ENUM -> new GenericData.EnumSymbol(schema, value). In Avro 1.11.x
        // the EnumSymbol constructor does NOT validate the symbol against the schema's symbol
        // set, so an out-of-schema symbol is stored WITHOUT error at conversion time. The bad
        // value only surfaces later (at serialize time) or never. This test pins the current
        // (permissive) behavior and documents the integrity gap.
        Schema enumSchema = SchemaBuilder.enumeration("Status").symbols("PENDING", "CAPTURED");
        Schema schema = SchemaBuilder.record("R").fields()
                .name("status").type(enumSchema).noDefault()
                .endRecord();

        GenericRecord record = converter.convert(Map.of("status", "REFUNDED"), schema);

        // No exception thrown; the invalid symbol is carried through unchecked.
        assertEquals("REFUNDED", record.get("status").toString());
    }
}
