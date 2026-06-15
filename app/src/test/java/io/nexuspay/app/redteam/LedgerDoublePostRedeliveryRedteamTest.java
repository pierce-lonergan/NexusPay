package io.nexuspay.app.redteam;

import io.nexuspay.app.IntegrationTestBase;
import io.nexuspay.app.config.TestSecurityConfig;
import io.nexuspay.common.event.EventTypes;
import io.nexuspay.ledger.adapter.in.event.PaymentEventConsumer;
import io.nexuspay.ledger.application.port.JournalEntryRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RED-TEAM (report-only, {@code @Tag("redteam")}): ledger double-post on Kafka
 * redelivery (B-010). This is the representative MONEY-INVARIANT red-team scenario.
 *
 * <p><strong>Attack / fault:</strong> Kafka at-least-once delivery (or a forged
 * replay) delivers the SAME capture event ({@code aggregate_id} + {@code event_id})
 * TWICE. A SECURE ledger books the capture EXACTLY ONCE — a duplicate must not
 * create a second journal entry that doubles the merchant receivable.</p>
 *
 * <p>The harness invokes {@link PaymentEventConsumer#onPaymentEvent(Map)} DIRECTLY
 * twice (deterministic — no broker timing flakiness), then concurrently to expose
 * the check-then-act race. It asserts {@code findByPaymentReference(paymentId)}
 * returns exactly ONE entry.</p>
 *
 * <p><strong>Why this FAILS on current main (excluded + report-only):</strong>
 * {@code PaymentEventConsumer} guards with
 * {@code existsByPaymentReferenceAndDescription(...)} — a check-then-act in
 * auto-commit BEFORE the SERIALIZABLE insert, with NO DB unique constraint behind
 * it. A redelivery that races the first insert (or simply arrives after the read
 * but before commit visibility) double-posts. When the SEC fix lands (a UNIQUE
 * constraint on {@code (payment_reference, description)} behind the check), drop
 * {@code @Tag("redteam")} to gate this.</p>
 */
@Tag("redteam")
@Import(TestSecurityConfig.class)
@DisplayName("RED-TEAM: ledger double-post on redelivery (B-010)")
class LedgerDoublePostRedeliveryRedteamTest extends IntegrationTestBase {

    @Autowired
    private PaymentEventConsumer consumer;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — ledger double-post red-team self-skips (Testcontainers required)");
    }

    private Map<String, Object> captureEvent(String paymentId, String eventId, long amount) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", amount);
        payload.put("currency", "USD");
        Map<String, Object> event = new HashMap<>();
        event.put("event_type", EventTypes.PAYMENT_CAPTURED);
        event.put("aggregate_id", paymentId);
        event.put("event_id", eventId);
        event.put("tenant_id", "default");
        event.put("payload", payload);
        return event;
    }

    @Test
    @DisplayName("sequential redelivery of the same capture books exactly ONE journal entry")
    void sequentialRedelivery_booksExactlyOnce() {
        String paymentId = "pi_redeliver_seq_" + UUID.randomUUID();
        String eventId = "evt_" + UUID.randomUUID();

        consumer.onPaymentEvent(captureEvent(paymentId, eventId, 10000L));
        consumer.onPaymentEvent(captureEvent(paymentId, eventId, 10000L)); // redelivery

        assertThat(journalEntryRepository.findByPaymentReference(paymentId))
                .as("a redelivered capture must not double-post the ledger")
                .hasSize(1);
    }

    @Test
    @DisplayName("CONCURRENT redelivery of the same capture still books exactly ONE entry")
    void concurrentRedelivery_booksExactlyOnce() throws Exception {
        String paymentId = "pi_redeliver_race_" + UUID.randomUUID();
        String eventId = "evt_" + UUID.randomUUID();

        int copies = 8;
        ExecutorService pool = Executors.newFixedThreadPool(copies);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(copies);
        try {
            for (int i = 0; i < copies; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        consumer.onPaymentEvent(captureEvent(paymentId, eventId, 10000L));
                    } catch (Exception ignored) {
                        // A losing writer may legitimately throw (constraint/serialization);
                        // the invariant is the FINAL entry count, asserted below.
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown(); // maximal contention on the check-then-act window
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(journalEntryRepository.findByPaymentReference(paymentId))
                .as("concurrent redeliveries must collapse to exactly one journal entry")
                .hasSize(1);
    }
}
