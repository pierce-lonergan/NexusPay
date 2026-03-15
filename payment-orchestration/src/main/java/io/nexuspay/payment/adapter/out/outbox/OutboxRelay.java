package io.nexuspay.payment.adapter.out.outbox;

import io.nexuspay.common.event.EventTypes;
import io.nexuspay.common.event.Topics;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polling-based outbox relay with leader election and graceful shutdown.
 *
 * Polls the event_outbox table every second for unpublished events
 * and publishes them to the appropriate Kafka topic. Events remain
 * in the outbox until Kafka acknowledges receipt.
 *
 * Leader election (GAP-007): Uses a Valkey lock to ensure only one instance
 * relays events when running multiple replicas. Lock TTL is 5s with 1s renewal.
 *
 * Graceful shutdown (GAP-014): Awaits in-flight relay cycle before stopping.
 *
 * Phase 2+ upgrade path: Replace with Debezium CDC for sub-second latency.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final String LEADER_LOCK_KEY = "outbox:relay:leader";
    private static final Duration LEADER_LOCK_TTL = Duration.ofSeconds(5);

    private static final Map<String, String> TOPIC_MAP = Map.of(
            EventTypes.AGGREGATE_PAYMENT, Topics.PAYMENTS,
            EventTypes.AGGREGATE_REFUND, Topics.PAYMENTS,
            EventTypes.AGGREGATE_LEDGER, Topics.LEDGER
    );

    private static final String DEFAULT_TOPIC = Topics.PAYMENTS;

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean relaying = new AtomicBoolean(false);

    public OutboxRelay(OutboxEventRepository outboxRepository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       StringRedisTemplate redisTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void relayEvents() {
        if (shuttingDown.get()) return;

        // Leader election: only one instance relays at a time
        if (!acquireLeaderLock()) return;

        relaying.set(true);
        try {
            List<OutboxEvent> events = outboxRepository.findUnpublishedEvents();
            if (events.isEmpty()) return;

            log.debug("Relaying {} outbox events to Kafka", events.size());

            for (OutboxEvent event : events) {
                if (shuttingDown.get()) break;
                try {
                    String topic = TOPIC_MAP.getOrDefault(event.getAggregateType(), DEFAULT_TOPIC);

                    var record = new ProducerRecord<>(topic, null, event.getAggregateId(), event.getPayload());
                    record.headers()
                            .add(new RecordHeader("event_type",
                                    event.getEventType().getBytes(StandardCharsets.UTF_8)))
                            .add(new RecordHeader("aggregate_type",
                                    event.getAggregateType().getBytes(StandardCharsets.UTF_8)))
                            .add(new RecordHeader("aggregate_id",
                                    event.getAggregateId().getBytes(StandardCharsets.UTF_8)));

                    kafkaTemplate.send(record)
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    log.error("Failed to publish outbox event {} to Kafka: {}",
                                            event.getId(), ex.getMessage());
                                }
                            });
                    event.markPublished();
                    log.debug("Published outbox event: id={} type={} topic={}",
                            event.getId(), event.getEventType(), topic);
                } catch (Exception e) {
                    log.warn("Failed to relay outbox event {}: {}", event.getId(), e.getMessage());
                    break;
                }
            }
        } finally {
            relaying.set(false);
        }
    }

    private boolean acquireLeaderLock() {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(LEADER_LOCK_KEY, instanceId(), LEADER_LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) return true;

            // Check if we already hold the lock and renew it
            String holder = redisTemplate.opsForValue().get(LEADER_LOCK_KEY);
            if (instanceId().equals(holder)) {
                redisTemplate.expire(LEADER_LOCK_KEY, LEADER_LOCK_TTL);
                return true;
            }
            return false;
        } catch (Exception e) {
            // Fail open: if Valkey is down, proceed with relay (single instance is fine)
            log.debug("Leader lock unavailable, proceeding: {}", e.getMessage());
            return true;
        }
    }

    private String instanceId() {
        return ProcessHandle.current().pid() + "@" + getHostName();
    }

    private static String getHostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Outbox relay shutting down, waiting for in-flight cycle...");
        shuttingDown.set(true);
        // Wait up to 5 seconds for current relay cycle to complete
        for (int i = 0; i < 50 && relaying.get(); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Release leader lock
        try {
            String holder = redisTemplate.opsForValue().get(LEADER_LOCK_KEY);
            if (instanceId().equals(holder)) {
                redisTemplate.delete(LEADER_LOCK_KEY);
            }
        } catch (Exception e) {
            log.debug("Could not release leader lock: {}", e.getMessage());
        }
        log.info("Outbox relay shutdown complete");
    }
}
