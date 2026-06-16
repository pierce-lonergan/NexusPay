package io.nexuspay.app.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.event.dlq.DeadLetterStatus;
import io.nexuspay.common.rls.SystemTransactional;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled reprocessor for dead letter queue entries.
 * Polls for retryable entries and republishes them to their original Kafka topic.
 *
 * <p>Exponential backoff: 2^n minutes (capped at 60 minutes).
 * Uses a Valkey distributed lock to ensure only one instance processes at a time.
 */
@Component
@ConditionalOnProperty(name = "nexuspay.dlq.reprocessor.enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterReprocessor {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterReprocessor.class);

    private static final String LOCK_KEY = "nexuspay:dlq:reprocessor:lock";
    private static final Duration LOCK_TTL = Duration.ofMinutes(2);
    private static final long MAX_BACKOFF_MINUTES = 60;

    /** Upper bound on waiting for a Kafka acknowledgment per entry (mirrors OutboxRelay). */
    private static final long SEND_ACK_TIMEOUT_SECONDS = 10;

    /**
     * SEC-16 lock-overrun bound: every entry now BLOCKS up to {@link #SEND_ACK_TIMEOUT_SECONDS} on the
     * broker ack INSIDE the lock-held window. With the old batch of 100 the worst case
     * (100 × 10s = ~16.7 min) dwarfed the {@link #LOCK_TTL} 2-minute lease — the lock would expire
     * mid-batch and a SECOND replica could start, double-processing the queue. We cap the per-cycle
     * batch so worst-case wall time stays well under the lease: target HALF the TTL as the safety
     * budget (leaving headroom for DB saves, GC pauses and clock skew), so
     * {@code BATCH_SIZE = (LOCK_TTL/2) / SEND_ACK_TIMEOUT}. At TTL=120s, timeout=10s -> 6 entries/cycle,
     * worst case ~60s << 120s. The reprocessor runs every 60s (fixedDelay), so the remaining backlog is
     * drained on subsequent cycles; throughput is bounded but correctness (single-writer) is preserved.
     */
    private static final int BATCH_SIZE =
            (int) ((LOCK_TTL.toSeconds() / 2) / SEND_ACK_TIMEOUT_SECONDS);

    private final DeadLetterRepository repository;
    private final KafkaTemplate<String, String> stringKafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int maxRetries;

    public DeadLetterReprocessor(DeadLetterRepository repository,
                                  KafkaTemplate<String, String> stringKafkaTemplate,
                                  StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  @Value("${nexuspay.dlq.reprocessor.max-retries:5}") int maxRetries) {
        this.repository = repository;
        this.stringKafkaTemplate = stringKafkaTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.maxRetries = maxRetries;
    }

    @SystemTransactional
    @Scheduled(fixedDelayString = "${nexuspay.dlq.reprocessor.fixed-delay-ms:60000}")
    public void reprocess() {
        // Distributed lock: only one instance processes at a time
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, "locked", LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("DLQ reprocessor lock not acquired, skipping");
            return;
        }

        try {
            List<DeadLetterEntry> entries = repository.findRetryable(
                    Instant.now(), PageRequest.of(0, BATCH_SIZE));

            if (entries.isEmpty()) {
                log.debug("No retryable DLQ entries found");
                return;
            }

            log.info("Processing {} retryable DLQ entries", entries.size());

            for (DeadLetterEntry entry : entries) {
                retryEntry(entry);
            }
        } finally {
            redisTemplate.delete(LOCK_KEY);
        }
    }

    private void retryEntry(DeadLetterEntry entry) {
        try {
            entry.setStatus(DeadLetterStatus.RETRYING);
            entry.setRetryCount(entry.getRetryCount() + 1);
            repository.save(entry);

            // SEC-16: BLOCK on the broker acknowledgment on THIS @SystemTransactional scheduler thread
            // (mirrors OutboxRelay), then mutate the terminal state SYNCHRONOUSLY. The prior
            // fire-and-forget whenComplete callback ran on the Kafka producer I/O thread AFTER the tx
            // committed and the Valkey lock released, off the SYSTEM role pin — and on the common
            // (success) path the row committed stuck in RETRYING, which findRetryable (PENDING-only)
            // never re-selects = a money/event dead letter permanently lost. Awaiting the ack here means
            // the RESOLVED write commits inside the tx, on the role-pinned thread, before the lock
            // releases — and a send timeout/failure throws into the catch below -> handleRetryFailure,
            // which sets PENDING/DISCARDED, also synchronously inside the tx.
            //
            // SEC-batch-1b (SEC-09): re-attach the ORIGINAL record headers — crucially the relay-stamped,
            // server-trusted `tenant_id` — on republish. A redelivered payment event that arrives
            // HEADER-LESS would resolve to tenant "default" in the webhook consumer (which trusts ONLY the
            // header, never the body), so it would silently miss the owning tenant's endpoints. Preserving
            // the captured headers keeps the redelivered event routed to its real tenant. DLT-internal
            // headers (kafka_dlt-*) are stripped so they do not ride along onto the original topic.
            republish(entry).get(SEND_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("DLQ entry {} successfully republished to {}", entry.getId(), entry.getOriginalTopic());
            entry.setStatus(DeadLetterStatus.RESOLVED);
            entry.setResolvedAt(Instant.now());
            repository.save(entry);
        } catch (Exception e) {
            log.error("Failed to retry DLQ entry {}: {}", entry.getId(), e.getMessage());
            handleRetryFailure(entry);
        }
    }

    /**
     * Republishes a dead letter to its original topic, re-attaching the ORIGINAL (server-trusted)
     * record headers captured at DLT-ingest time — most importantly the relay-stamped {@code tenant_id}
     * the webhook consumer authorizes on. When no usable headers were captured, falls back to the plain
     * key/value send (pre-existing behavior). The returned future is awaited by the caller so the ack is
     * observed synchronously inside the @SystemTransactional window.
     */
    private java.util.concurrent.CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>
            republish(DeadLetterEntry entry) {
        Map<String, String> headers = parseHeaders(entry.getEventHeaders());
        if (headers.isEmpty()) {
            // No captured headers (legacy/old entries) — preserve the original 3-arg send.
            return stringKafkaTemplate.send(
                    entry.getOriginalTopic(), entry.getEventKey(), entry.getEventValue());
        }
        var record = new ProducerRecord<>(
                entry.getOriginalTopic(), null, entry.getEventKey(), entry.getEventValue());
        headers.forEach((k, v) -> {
            if (v != null) {
                record.headers().add(new RecordHeader(k, v.getBytes(StandardCharsets.UTF_8)));
            }
        });
        return stringKafkaTemplate.send(record);
    }

    /**
     * Deserializes the JSON header map captured by {@code DeadLetterQueueConsumer.serializeHeaders}, then
     * STRIPS the Spring Kafka DLT-internal headers ({@code kafka_dlt-*}) so they are not republished onto
     * the original topic. Returns an empty map (never null) on absent/blank/unparseable input so the
     * caller falls back to the plain send.
     */
    private Map<String, String> parseHeaders(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> all = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
            all.entrySet().removeIf(e -> e.getKey() == null || e.getKey().startsWith("kafka_dlt-"));
            return all;
        } catch (Exception e) {
            log.warn("Failed to parse stored DLT headers — republishing without headers: {}",
                    e.getMessage());
            return Map.of();
        }
    }

    private void handleRetryFailure(DeadLetterEntry entry) {
        if (entry.getRetryCount() >= maxRetries) {
            log.warn("DLQ entry {} exhausted retries ({}), marking DISCARDED", entry.getId(), maxRetries);
            entry.setStatus(DeadLetterStatus.DISCARDED);
            entry.setResolvedAt(Instant.now());
        } else {
            entry.setStatus(DeadLetterStatus.PENDING);
            // Exponential backoff: 2^n minutes, capped at 60 min
            long backoffMinutes = Math.min((long) Math.pow(2, entry.getRetryCount()), MAX_BACKOFF_MINUTES);
            entry.setNextRetryAt(Instant.now().plusSeconds(backoffMinutes * 60));
        }
        repository.save(entry);
    }
}
