package io.nexuspay.app.event;

import io.nexuspay.common.event.dlq.DeadLetterStatus;
import io.nexuspay.common.rls.SystemTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
    private static final int BATCH_SIZE = 100;
    private static final long MAX_BACKOFF_MINUTES = 60;

    private final DeadLetterRepository repository;
    private final KafkaTemplate<String, String> stringKafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private final int maxRetries;

    public DeadLetterReprocessor(DeadLetterRepository repository,
                                  KafkaTemplate<String, String> stringKafkaTemplate,
                                  StringRedisTemplate redisTemplate,
                                  @Value("${nexuspay.dlq.reprocessor.max-retries:5}") int maxRetries) {
        this.repository = repository;
        this.stringKafkaTemplate = stringKafkaTemplate;
        this.redisTemplate = redisTemplate;
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

            // Republish to original topic
            stringKafkaTemplate.send(entry.getOriginalTopic(), entry.getEventKey(), entry.getEventValue())
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("DLQ retry failed for entry {}: {}", entry.getId(), ex.getMessage());
                            handleRetryFailure(entry);
                        } else {
                            log.info("DLQ entry {} successfully republished to {}", entry.getId(), entry.getOriginalTopic());
                            entry.setStatus(DeadLetterStatus.RESOLVED);
                            entry.setResolvedAt(Instant.now());
                            repository.save(entry);
                        }
                    });
        } catch (Exception e) {
            log.error("Failed to retry DLQ entry {}", entry.getId(), e);
            handleRetryFailure(entry);
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
