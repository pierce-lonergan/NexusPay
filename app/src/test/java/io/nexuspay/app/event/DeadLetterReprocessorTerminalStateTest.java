package io.nexuspay.app.event;

import io.nexuspay.common.event.dlq.DeadLetterStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-16 — proves a dead letter ALWAYS reaches a terminal/queryable state SYNCHRONOUSLY inside
 * {@link DeadLetterReprocessor#reprocess()}, and is NEVER left stuck in {@code RETRYING}.
 *
 * <p>Plain JUnit + Mockito unit test (no {@code IntegrationTestBase}, no Docker). It mocks the
 * repository, the Valkey lock (StringRedisTemplate), and the KafkaTemplate.</p>
 *
 * <p><strong>Why the producer future completes ASYNCHRONOUSLY (on a background thread) rather than
 * being pre-completed:</strong> a pre-completed {@code CompletableFuture.completedFuture(...)} runs a
 * {@code whenComplete} callback INLINE on the caller thread, so the buggy async code would
 * (mis-)appear to mutate terminal state synchronously and the test would not distinguish the fix. By
 * completing the future from a separate thread AFTER {@code reprocess()} has been invoked, the two
 * implementations diverge exactly as in production: the SYNCHRONOUS {@code .get(timeout)} fix BLOCKS
 * until the ack arrives and then mutates terminal state before returning; the OLD async
 * {@code whenComplete} returns immediately leaving the row in RETRYING, and only mutates terminal
 * state later off-thread. The assertion is taken right after {@code reprocess()} returns — so the old
 * code is caught with a RETRYING row, and only the fix passes.</p>
 *
 * <p>On every path the LAST status saved is asserted to be a terminal state (RESOLVED / PENDING /
 * DISCARDED), never RETRYING.</p>
 */
@DisplayName("SEC-16: dead letter reaches a terminal state synchronously, never stuck RETRYING")
class DeadLetterReprocessorTerminalStateTest {

    private DeadLetterRepository repository;
    private KafkaTemplate<String, String> kafkaTemplate;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(DeadLetterRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        redisTemplate = mock(StringRedisTemplate.class);

        // Lock acquisition path (reprocess(): setIfAbsent -> true so we proceed; delete in finally).
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    private DeadLetterReprocessor newReprocessor(int maxRetries) {
        return new DeadLetterReprocessor(repository, kafkaTemplate, redisTemplate, maxRetries);
    }

    private DeadLetterEntry entryWith(int retryCount, int maxRetries) {
        DeadLetterEntry entry = new DeadLetterEntry();
        entry.setOriginalTopic("nexuspay.payments");
        entry.setEventKey("evt-key");
        entry.setEventValue("{\"event\":\"x\"}");
        entry.setStatus(DeadLetterStatus.PENDING);
        entry.setRetryCount(retryCount);
        entry.setMaxRetries(maxRetries);
        return entry;
    }

    private DeadLetterStatus lastSavedStatus() {
        ArgumentCaptor<DeadLetterEntry> captor = ArgumentCaptor.forClass(DeadLetterEntry.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        List<DeadLetterEntry> saved = captor.getAllValues();
        return saved.get(saved.size() - 1).getStatus();
    }

    /**
     * A future that is NOT yet complete when returned; a background thread completes it ~80ms later.
     * The synchronous {@code .get(timeout)} fix blocks on it (so the terminal mutation runs before
     * reprocess() returns); the old async whenComplete returns first and is caught mid-RETRYING.
     *
     * @param failure if non-null, the future completes exceptionally with it; otherwise it completes OK
     */
    private CompletableFuture<SendResult<String, String>> asyncFuture(Throwable failure) {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = mock(SendResult.class);
        Thread completer = new Thread(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (failure != null) {
                future.completeExceptionally(failure);
            } else {
                future.complete(sendResult);
            }
        }, "dlq-test-ack-completer");
        completer.setDaemon(true);
        completer.start();
        return future;
    }

    @Test
    @DisplayName("send ack OK -> RESOLVED synchronously (resolvedAt set), never left RETRYING")
    void sendAcknowledged_resolvesSynchronously() {
        DeadLetterEntry entry = entryWith(0, 5);
        when(repository.findRetryable(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(entry));
        // Broker ack arrives ASYNCHRONOUSLY (background thread) — the sync .get() fix must block for it.
        when(kafkaTemplate.send(eq("nexuspay.payments"), eq("evt-key"), anyString()))
                .thenReturn(asyncFuture(null));

        newReprocessor(5).reprocess();

        assertThat(entry.getStatus()).isEqualTo(DeadLetterStatus.RESOLVED);
        assertThat(entry.getResolvedAt()).isNotNull();
        assertThat(lastSavedStatus())
                .as("after reprocess() returns, the row must be terminal, not RETRYING")
                .isEqualTo(DeadLetterStatus.RESOLVED)
                .isNotEqualTo(DeadLetterStatus.RETRYING);
    }

    @Test
    @DisplayName("send fails with retries remaining -> PENDING + backoff synchronously, never left RETRYING")
    void sendFails_retriesRemaining_pendingWithBackoff() {
        DeadLetterEntry entry = entryWith(0, 5);
        when(repository.findRetryable(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(entry));
        // Broker failure arrives ASYNCHRONOUSLY: the sync .get() throws ExecutionException -> catch ->
        // handleRetryFailure, all before reprocess() returns.
        when(kafkaTemplate.send(eq("nexuspay.payments"), eq("evt-key"), anyString()))
                .thenReturn(asyncFuture(new RuntimeException("broker unavailable")));

        newReprocessor(5).reprocess();

        assertThat(entry.getStatus()).isEqualTo(DeadLetterStatus.PENDING);
        assertThat(entry.getNextRetryAt())
                .as("a PENDING re-queue must carry a future nextRetryAt so findRetryable can re-select it")
                .isNotNull();
        assertThat(lastSavedStatus())
                .as("after reprocess() returns, the row must be terminal/queryable (PENDING), not RETRYING")
                .isEqualTo(DeadLetterStatus.PENDING)
                .isNotEqualTo(DeadLetterStatus.RETRYING);
    }

    @Test
    @DisplayName("send fails with retries exhausted -> DISCARDED synchronously, never left RETRYING")
    void sendFails_retriesExhausted_discarded() {
        // retryCount becomes 5 after the pre-send increment; maxRetries=5 -> exhausted.
        DeadLetterEntry entry = entryWith(4, 5);
        when(repository.findRetryable(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(entry));
        when(kafkaTemplate.send(eq("nexuspay.payments"), eq("evt-key"), anyString()))
                .thenReturn(asyncFuture(new RuntimeException("broker unavailable")));

        newReprocessor(5).reprocess();

        assertThat(entry.getStatus()).isEqualTo(DeadLetterStatus.DISCARDED);
        assertThat(entry.getResolvedAt()).isNotNull();
        assertThat(lastSavedStatus())
                .as("after reprocess() returns, an exhausted entry must be DISCARDED, not RETRYING")
                .isEqualTo(DeadLetterStatus.DISCARDED)
                .isNotEqualTo(DeadLetterStatus.RETRYING);
    }
}
