package io.nexuspay.app.redteam;

import io.nexuspay.app.IntegrationTestBase;
import io.nexuspay.app.config.TestSecurityConfig;
import io.nexuspay.common.event.EventTypes;
import io.nexuspay.ledger.adapter.in.event.PaymentEventConsumer;
import io.nexuspay.ledger.application.port.JournalEntryRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * SEC-10 GATE (permanent guard, formerly {@code @Tag("redteam")}): ledger double-post on Kafka
 * redelivery (B-010). This is the representative MONEY-INVARIANT scenario.
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
 * <p><strong>Why it is now GATED (SEC-10):</strong> {@code PaymentEventConsumer}
 * still uses {@code existsByPaymentReferenceAndDescription(...)} as the cheap fast
 * path, but the UNIQUE index {@code uq_journal_entries_payment_ref_desc} on
 * {@code (payment_reference, description)} (V4028) is now the race backstop, and
 * {@code CreateJournalEntryUseCase} uses {@code saveAndFlush} + a narrowed dup-key
 * no-op so a losing redelivery returns cleanly (no second post, no DLT) instead of
 * double-posting. The {@code @Tag("redteam")} exclusion has been removed so this
 * runs in the default gate as a permanent SEC-10 guard; it would FAIL on the old
 * (no-constraint, plain-{@code save}) behavior.</p>
 */
@Import(TestSecurityConfig.class)
@DisplayName("SEC-10 GATE: ledger books exactly once on redelivery (B-010)")
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
        // SEC-10: the redelivery must be a SILENT no-op — the dup-key no-op returns cleanly rather
        // than throwing (a throw would propagate to the consumer -> retry/DLT), so a true redelivery
        // does not surface as an error.
        assertThatCode(() -> consumer.onPaymentEvent(captureEvent(paymentId, eventId, 10000L)))
                .as("a redelivered capture must be a silent no-op, never a thrown error -> DLT")
                .doesNotThrowAnyException();

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
