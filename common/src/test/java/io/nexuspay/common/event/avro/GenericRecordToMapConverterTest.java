package io.nexuspay.common.event.avro;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.util.Utf8;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GenericRecordToMapConverter}, the inverse of {@link JsonToAvroConverter}.
 *
 * <p>This converter feeds every JSON Kafka consumer for Avro-format messages, so a mis-shaped
 * Map silently corrupts every consumed financial event. Round-trip tests against
 * {@link JsonToAvroConverter} give strong structural assertions.
 */
class GenericRecordToMapConverterTest {

    private final GenericRecordToMapConverter converter = new GenericRecordToMapConverter();
    private final JsonToAvroConverter toAvro = new JsonToAvroConverter();

    // ---------- STRING / Utf8 unwrapping ----------

    @Test
    void unwrapsUtf8ToJavaLangString() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("s").type().stringType().noDefault()
                .endRecord();
        GenericRecord record = new GenericData.Record(schema);
        // Avro deserialization yields Utf8 for string fields, not java.lang.String
        record.put("s", new Utf8("hello"));

        Map<String, Object> map = converter.convert(record);

        Object value = map.get("s");
        assertEquals("hello", value);
        assertEquals(String.class, value.getClass(), "Utf8 must be unwrapped to java.lang.String");
    }

    // ---------- LONG: timestamp-millis logical type vs plain long ----------

    @Test
    void timestampMillisLongConvertsToIso8601String() {
        Schema tsSchema = Schema.create(Schema.Type.LONG);
        tsSchema.addProp("logicalType", "timestamp-millis");
        Schema schema = Schema.createRecord("R", null, null, false);
        schema.setFields(List.of(new Schema.Field("ts", tsSchema, null, (Object) null)));

        long millis = Instant.parse("2026-03-15T12:00:00Z").toEpochMilli();
        GenericRecord record = new GenericData.Record(schema);
        record.put("ts", millis);

        Map<String, Object> map = converter.convert(record);

        assertEquals("2026-03-15T12:00:00Z", map.get("ts"));
    }

    @Test
    void plainLongStaysLong() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("amount").type().longType().noDefault()
                .endRecord();
        GenericRecord record = new GenericData.Record(schema);
        record.put("amount", 2599L);

        Map<String, Object> map = converter.convert(record);

        Object value = map.get("amount");
        assertEquals(2599L, value);
        assertEquals(Long.class, value.getClass());
    }

    // ---------- nested RECORD ----------

    @Test
    void nestedRecordBecomesNestedMap() {
        Schema money = SchemaBuilder.record("Money").fields()
                .name("amount").type().longType().noDefault()
                .name("currency").type().stringType().noDefault()
                .endRecord();
        Schema schema = SchemaBuilder.record("Payment").fields()
                .name("money").type(money).noDefault()
                .endRecord();

        GenericRecord moneyRec = new GenericData.Record(money);
        moneyRec.put("amount", 500L);
        moneyRec.put("currency", new Utf8("EUR"));
        GenericRecord payment = new GenericData.Record(schema);
        payment.put("money", moneyRec);

        Map<String, Object> map = converter.convert(payment);

        Object nested = map.get("money");
        assertInstanceOf(Map.class, nested);
        @SuppressWarnings("unchecked")
        Map<String, Object> moneyMap = (Map<String, Object>) nested;
        assertEquals(500L, moneyMap.get("amount"));
        assertEquals("EUR", moneyMap.get("currency"));
    }

    // ---------- ARRAY ----------

    @Test
    void arrayElementsAreUnwrapped() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("tags").type().array().items().stringType().noDefault()
                .endRecord();
        GenericRecord record = new GenericData.Record(schema);
        record.put("tags", List.of(new Utf8("a"), new Utf8("b")));

        Map<String, Object> map = converter.convert(record);

        Object value = map.get("tags");
        assertEquals(List.of("a", "b"), value);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) value;
        assertEquals(String.class, list.get(0).getClass(), "array string elements must be unwrapped to String");
    }

    // ---------- ENUM ----------

    @Test
    void enumBecomesStringSymbol() {
        Schema enumSchema = SchemaBuilder.enumeration("Status").symbols("PENDING", "CAPTURED");
        Schema schema = SchemaBuilder.record("R").fields()
                .name("status").type(enumSchema).noDefault()
                .endRecord();
        GenericRecord record = new GenericData.Record(schema);
        record.put("status", new GenericData.EnumSymbol(enumSchema, "CAPTURED"));

        Map<String, Object> map = converter.convert(record);

        Object value = map.get("status");
        assertEquals("CAPTURED", value);
        assertEquals(String.class, value.getClass());
    }

    // ---------- UNION (nullable) ----------

    @Test
    void nullableUnionNullStaysNull() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("opt").type().unionOf().nullType().and().longType().endUnion().nullDefault()
                .endRecord();
        GenericRecord record = new GenericData.Record(schema);
        record.put("opt", null);

        Map<String, Object> map = converter.convert(record);

        assertTrue(map.containsKey("opt"));
        assertNull(map.get("opt"));
    }

    @Test
    void nullableUnionPresentValueResolvesToNonNullBranch() {
        Schema schema = SchemaBuilder.record("R").fields()
                .name("opt").type().unionOf().nullType().and().longType().endUnion().nullDefault()
                .endRecord();
        GenericRecord record = new GenericData.Record(schema);
        record.put("opt", 99L);

        Map<String, Object> map = converter.convert(record);

        assertEquals(99L, map.get("opt"));
    }

    // ---------- BUG-WATCH: multi-type union picks the first non-null branch ----------

    @Test
    void multiTypeUnionPicksFirstNonNullBranchRegardlessOfValueType() {
        // resolveUnionSchema returns the FIRST non-null branch, ignoring the value's real type.
        // For union ["null","int","string"] holding a STRING, it resolves the schema as INT and
        // routes the value down the INT/FLOAT/DOUBLE/BOOLEAN pass-through branch.
        Schema schema = SchemaBuilder.record("R").fields()
                .name("u").type().unionOf().nullType().and().intType().and().stringType().endUnion().noDefault()
                .endRecord();
        GenericRecord record = new GenericData.Record(schema);
        // Put a string into a union whose first non-null branch is INT
        record.put("u", new Utf8("oops"));

        Map<String, Object> map = converter.convert(record);

        Object value = map.get("u");
        // BUG: because INT is resolved, the INT branch does `case INT,...-> value`, so the
        // Utf8 is returned RAW (not toString()-unwrapped). A correct resolver would have
        // picked the STRING branch and returned a java.lang.String.
        assertNotEquals(String.class, value.getClass(),
                "documents the union-resolution bug: string value mis-routed through INT branch, left as Utf8");
        assertInstanceOf(CharSequence.class, value);
        assertEquals("oops", value.toString());
    }

    // ---------- round-trip with JsonToAvroConverter ----------

    @Test
    void roundTripPreservesStructureWithDocumentedTransforms() {
        Schema money = SchemaBuilder.record("Money").fields()
                .name("amount").type().longType().noDefault()
                .name("currency").type().stringType().noDefault()
                .endRecord();
        Schema schema = SchemaBuilder.record("PaymentCaptured").fields()
                .name("payment_id").type().stringType().noDefault()
                .name("money").type(money).noDefault()
                .name("retry_count").type().intType().noDefault()
                .endRecord();

        Map<String, Object> original = Map.of(
                "payment_id", "pi_123",
                "money", Map.of("amount", "7500", "currency", "USD"),
                "retry_count", 2
        );

        GenericRecord record = toAvro.convert(original, schema);
        Map<String, Object> back = converter.convert(record);

        assertEquals("pi_123", back.get("payment_id"));
        assertEquals(2, back.get("retry_count"));
        @SuppressWarnings("unchecked")
        Map<String, Object> moneyMap = (Map<String, Object>) back.get("money");
        assertEquals(7500L, moneyMap.get("amount")); // string "7500" -> long 7500 survives round trip
        assertEquals("USD", moneyMap.get("currency"));
    }

    @Test
    void roundTripWithTimestampMillisYieldsIsoString() {
        Schema tsSchema = Schema.create(Schema.Type.LONG);
        tsSchema.addProp("logicalType", "timestamp-millis");
        Schema schema = Schema.createRecord("Evt", null, null, false);
        schema.setFields(List.of(
                new Schema.Field("event_id", Schema.create(Schema.Type.STRING), null, (Object) null),
                new Schema.Field("ts", tsSchema, null, (Object) null)
        ));

        String iso = "2026-03-15T12:00:00Z";
        Map<String, Object> original = Map.of("event_id", "evt_1", "ts", iso);

        GenericRecord record = toAvro.convert(original, schema);
        // JsonToAvro coerced the ISO string to epoch-millis (a long)
        assertEquals(Instant.parse(iso).toEpochMilli(), record.get("ts"));

        Map<String, Object> back = converter.convert(record);
        // GenericRecordToMap converts the timestamp-millis long back to the ISO string
        assertEquals(iso, back.get("ts"));
        assertEquals("evt_1", back.get("event_id"));
    }
}
