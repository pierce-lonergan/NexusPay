package io.nexuspay.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EventEnvelope#create} — the factory used to build every Kafka event.
 * Verifies the defaults (version=1, timestamp=now) that downstream serialization depends on,
 * and that all passed fields round-trip into the record components unchanged.
 */
class EventEnvelopeTest {

    @Test
    void createSetsVersionToOneAndTimestampToNow() {
        Instant before = Instant.now();

        EventEnvelope env = EventEnvelope.create(
                "evt_1", "PaymentCaptured", "Payment", "pi_9",
                Map.of("trace_id", "t1"),
                Map.of("amount", 7500));

        Instant after = Instant.now();

        assertEquals(1, env.version());
        assertNotNull(env.timestamp());
        // timestamp ~ now, within the test window
        assertFalse(env.timestamp().isBefore(before), "timestamp should be >= before");
        assertFalse(env.timestamp().isAfter(after), "timestamp should be <= after");
    }

    @Test
    void createRoundTripsAllPassedFields() {
        Map<String, String> metadata = Map.of("trace_id", "t1", "tenant_id", "acme");
        Map<String, Object> payload = Map.of("amount", 7500, "currency", "USD");

        EventEnvelope env = EventEnvelope.create(
                "evt_1", "PaymentCaptured", "Payment", "pi_9", metadata, payload);

        assertEquals("evt_1", env.event_id());
        assertEquals("PaymentCaptured", env.event_type());
        assertEquals("Payment", env.aggregate_type());
        assertEquals("pi_9", env.aggregate_id());
        assertEquals(metadata, env.metadata());
        assertEquals(payload, env.payload());
    }
}
