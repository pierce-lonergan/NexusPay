package io.nexuspay.common.event;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EventUpcasterChain}, the event-schema-evolution state machine.
 *
 * <p>Each test feeds anonymous {@link EventUpcaster} implementations and asserts the chain
 * walk is correct — a wrong walk means a consumer processes a stale or half-migrated
 * financial event.
 */
class EventUpcasterChainTest {

    /** Simple string upcaster that appends a marker so we can trace the walk. */
    private static EventUpcaster upcaster(String type, int from, int to, String marker) {
        return new EventUpcaster() {
            @Override public String eventType() { return type; }
            @Override public int fromVersion() { return from; }
            @Override public int toVersion() { return to; }
            @Override public String upcast(String payload) { return payload + marker; }
        };
    }

    // ---------- full chain walk ----------

    @Test
    void walksFullChainV1ToV3ApplyingEachStepInOrder() {
        EventUpcasterChain chain = new EventUpcasterChain(List.of(
                upcaster("PaymentCaptured", 1, 2, "|v2"),
                upcaster("PaymentCaptured", 2, 3, "|v3")
        ));

        String result = chain.upcast("PaymentCaptured", 1, "base");

        assertEquals("base|v2|v3", result);
    }

    @Test
    void registrationOrderDoesNotAffectWalkOrder() {
        // Register out of order; the TreeMap keyed by fromVersion must still walk 1->2->3.
        EventUpcasterChain chain = new EventUpcasterChain(List.of(
                upcaster("Evt", 2, 3, "|v3"),
                upcaster("Evt", 1, 2, "|v2")
        ));

        assertEquals("base|v2|v3", chain.upcast("Evt", 1, "base"));
    }

    // ---------- latestVersion ----------

    @Test
    void latestVersionReturnsLastUpcasterToVersion() {
        EventUpcasterChain chain = new EventUpcasterChain(List.of(
                upcaster("Evt", 1, 2, "a"),
                upcaster("Evt", 2, 3, "b"),
                upcaster("Evt", 3, 4, "c")
        ));

        assertEquals(4, chain.latestVersion("Evt"));
    }

    @Test
    void latestVersionReturnsBaselineOneForUnknownType() {
        EventUpcasterChain chain = new EventUpcasterChain(List.of(
                upcaster("Evt", 1, 2, "a")
        ));

        assertEquals(1, chain.latestVersion("DoesNotExist"));
    }

    @Test
    void latestVersionReturnsOneForEmptyChain() {
        EventUpcasterChain chain = new EventUpcasterChain(List.of());
        assertEquals(1, chain.latestVersion("Anything"));
    }

    // ---------- identity / no-op paths ----------

    @Test
    void unknownEventTypeReturnsPayloadUnchanged() {
        EventUpcasterChain chain = new EventUpcasterChain(List.of(
                upcaster("Known", 1, 2, "|v2")
        ));

        assertEquals("raw", chain.upcast("Unknown", 1, "raw"));
    }

    @Test
    void emptyChainReturnsPayloadUnchanged() {
        EventUpcasterChain chain = new EventUpcasterChain(List.of());
        assertEquals("raw", chain.upcast("Anything", 1, "raw"));
    }

    @Test
    void fromVersionAtLatestReturnsInputUnchanged() {
        EventUpcasterChain chain = new EventUpcasterChain(List.of(
                upcaster("Evt", 1, 2, "|v2"),
                upcaster("Evt", 2, 3, "|v3")
        ));

        // Already at version 3 (latest): no key 3 in the chain, loop never enters.
        assertEquals("already-latest", chain.upcast("Evt", 3, "already-latest"));
    }

    @Test
    void fromVersionAboveLatestReturnsInputUnchanged() {
        EventUpcasterChain chain = new EventUpcasterChain(List.of(
                upcaster("Evt", 1, 2, "|v2")
        ));

        assertEquals("future", chain.upcast("Evt", 99, "future"));
    }

    // ---------- partial walk from middle of chain ----------

    @Test
    void fromVersionInMiddleAppliesOnlyRemainingSteps() {
        EventUpcasterChain chain = new EventUpcasterChain(List.of(
                upcaster("Evt", 1, 2, "|v2"),
                upcaster("Evt", 2, 3, "|v3")
        ));

        // Starting at v2: only the v2->v3 step should apply.
        assertEquals("mid|v3", chain.upcast("Evt", 2, "mid"));
    }

    // ---------- BUG-WATCH: gap in chain stops the walk silently ----------

    @Test
    void gapInChainStopsWalkAndReturnsHalfMigratedPayload() {
        // v1->v2 and v3->v4 registered, but v2->v3 is MISSING. The constructor only WARNS
        // (does not throw). upcast walks 1->2, then looks for key 2's continuation... wait,
        // after v1->v2 currentVersion becomes 2; key 2 is absent (the gap), so the loop stops.
        // The payload is returned at v2 — silently half-migrated.
        EventUpcasterChain chain = new EventUpcasterChain(List.of(
                upcaster("Evt", 1, 2, "|v2"),
                upcaster("Evt", 3, 4, "|v4")
        ));

        String result = chain.upcast("Evt", 1, "base");

        // Stops at the gap: only the v1->v2 transform applied. The v3->v4 step is unreachable.
        assertEquals("base|v2", result);
        // And latestVersion still reports 4 (last toVersion), so callers THINK it reached v4
        // even though the walk stranded the payload at v2 — the documented risk.
        assertEquals(4, chain.latestVersion("Evt"));
    }

    @Test
    void noncontiguousChainHaltsWhenCurrentVersionKeyMissing() {
        // fromVersion lands on a key that has no upcaster (1 and 3 registered, start at 2).
        EventUpcasterChain chain = new EventUpcasterChain(List.of(
                upcaster("Evt", 1, 2, "|v2"),
                upcaster("Evt", 3, 4, "|v4")
        ));

        // Start at v2: no key 2 -> loop never enters -> unchanged.
        assertEquals("stranded", chain.upcast("Evt", 2, "stranded"));
    }

    // ---------- Avro GenericRecord overload ----------

    @Test
    void avroOverloadWalksChainViaGenericRecordUpcast() {
        Schema schema = SchemaBuilder.record("Evt").fields()
                .name("v").type().intType().noDefault()
                .endRecord();

        // Each upcaster bumps the "v" field on the record (Avro-native upcast override).
        EventUpcaster v1to2 = new EventUpcaster() {
            @Override public String eventType() { return "Evt"; }
            @Override public int fromVersion() { return 1; }
            @Override public int toVersion() { return 2; }
            @Override public String upcast(String payload) { return payload; }
            @Override public GenericRecord upcast(GenericRecord record) {
                record.put("v", 2);
                return record;
            }
        };
        EventUpcaster v2to3 = new EventUpcaster() {
            @Override public String eventType() { return "Evt"; }
            @Override public int fromVersion() { return 2; }
            @Override public int toVersion() { return 3; }
            @Override public String upcast(String payload) { return payload; }
            @Override public GenericRecord upcast(GenericRecord record) {
                record.put("v", 3);
                return record;
            }
        };

        EventUpcasterChain chain = new EventUpcasterChain(List.of(v1to2, v2to3));

        GenericRecord record = new GenericData.Record(schema);
        record.put("v", 1);

        GenericRecord result = chain.upcast("Evt", 1, record);

        assertEquals(3, result.get("v"));
    }

    @Test
    void avroOverloadUnknownTypeReturnsRecordUnchanged() {
        Schema schema = SchemaBuilder.record("Evt").fields()
                .name("v").type().intType().noDefault()
                .endRecord();
        GenericRecord record = new GenericData.Record(schema);
        record.put("v", 1);

        EventUpcasterChain chain = new EventUpcasterChain(List.of());

        GenericRecord result = chain.upcast("Unknown", 1, record);

        assertSame(record, result);
        assertEquals(1, result.get("v"));
    }
}
