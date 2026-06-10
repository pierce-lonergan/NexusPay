package io.nexuspay.payment.adapter.out.outbox;

import io.nexuspay.common.event.EventTypes;
import io.nexuspay.common.event.Topics;
import io.nexuspay.common.event.avro.DualWritePublisher;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
 * <p><b>Sprint 2.2 migration:</b> When Debezium CDC is active, disable this relay
 * with {@code nexuspay.outbox.polling.enabled=false}. Both can run in parallel
 * during migration (consumers dedup by event_id). Once CDC is verified stable,
 * disable polling permanently.</p>
 */
@Component
@ConditionalOnProperty(name = "nexuspay.outbox.polling.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final String LEADER_LOCK_KEY = "outbox:relay:leader";
    private static final Duration LEADER_LOCK_TTL = Duration.ofSeconds(5);

    // Atomic owner-checked release — cannot delete a lock another instance has
    // since acquired (the prior GET-then-DELETE had a TOCTOU window). Mirrors
    // billing SchedulerLock (B-018).
    private static final RedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private static final Map<String, String> TOPIC_MAP = Map.ofEntries(
            Map.entry(EventTypes.AGGREGATE_PAYMENT, Topics.PAYMENTS),
            Map.entry(EventTypes.AGGREGATE_REFUND, Topics.PAYMENTS),
            Map.entry(EventTypes.AGGREGATE_LEDGER, Topics.LEDGER),
            Map.entry(EventTypes.AGGREGATE_SUBSCRIPTION, Topics.BILLING),
            Map.entry(EventTypes.AGGREGATE_INVOICE, Topics.BILLING),
            Map.entry(EventTypes.AGGREGATE_FRAUD_ASSESSMENT, Topics.FRAUD_ASSESSMENTS),
            Map.entry(EventTypes.AGGREGATE_FRAUD_RULE, Topics.FRAUD_RULES_CHANGELOG),
            Map.entry(EventTypes.AGGREGATE_FX_RATE, Topics.FX_RATES),
            Map.entry(EventTypes.AGGREGATE_FX_LOCK, Topics.FX_LOCKS),
            Map.entry(EventTypes.AGGREGATE_FX_CONVERSION, Topics.FX_CONVERSIONS),
            Map.entry(EventTypes.AGGREGATE_ROUTING_DECISION, Topics.ROUTING_DECISIONS),
            Map.entry(EventTypes.AGGREGATE_CONNECTED_ACCOUNT, Topics.MARKETPLACE_EVENTS),
            Map.entry(EventTypes.AGGREGATE_SPLIT_PAYMENT, Topics.MARKETPLACE_EVENTS),
            Map.entry(EventTypes.AGGREGATE_PAYOUT, Topics.MARKETPLACE_EVENTS),
            Map.entry(EventTypes.AGGREGATE_PURCHASE_ORDER, Topics.B2B_EVENTS),
            Map.entry(EventTypes.AGGREGATE_B2B_INVOICE, Topics.B2B_EVENTS),
            Map.entry(EventTypes.AGGREGATE_VIRTUAL_CARD, Topics.B2B_EVENTS),
            Map.entry(EventTypes.AGGREGATE_VENDOR_PAYMENT, Topics.B2B_EVENTS),
            Map.entry(EventTypes.AGGREGATE_WORKFLOW_DEFINITION, Topics.WORKFLOW_EVENTS),
            Map.entry(EventTypes.AGGREGATE_WORKFLOW_EXECUTION, Topics.WORKFLOW_EVENTS),
            Map.entry(EventTypes.AGGREGATE_WEBHOOK_TRIGGER, Topics.WORKFLOW_EVENTS)
    );

    private static final String DEFAULT_TOPIC = Topics.PAYMENTS;

    /** Upper bound on waiting for a Kafka acknowledgment per event. */
    private static final long SEND_ACK_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean relaying = new AtomicBoolean(false);

    /**
     * Optional DualWritePublisher — present when dual-write infrastructure is available.
     * Falls back to direct KafkaTemplate publish when absent.
     */
    private final DualWritePublisher dualWritePublisher;

    /**
     * Optional post-publish callback for event log appending.
     * Set by configuration to call EventLogAppender.append() when event log is enabled.
     */
    private final PostPublishCallback postPublishCallback;

    public OutboxRelay(OutboxEventRepository outboxRepository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       StringRedisTemplate redisTemplate,
                       ObjectProvider<DualWritePublisher> dualWritePublisherProvider,
                       ObjectProvider<PostPublishCallback> postPublishCallbackProvider) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;
        this.dualWritePublisher = dualWritePublisherProvider.getIfAvailable();
        this.postPublishCallback = postPublishCallbackProvider.getIfAvailable();
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
                    Map<String, String> eventHeaders = Map.of(
                            "event_type", event.getEventType(),
                            "aggregate_type", event.getAggregateType(),
                            "aggregate_id", event.getAggregateId());

                    // Await the broker acknowledgment BEFORE marking the event
                    // published. An async send + immediate markPublished would
                    // commit publishedAt even when the broker is down, silently
                    // dropping the event from the at-least-once pipeline.
                    if (dualWritePublisher != null) {
                        // Delegate to DualWritePublisher for format-aware publishing
                        dualWritePublisher.publish(topic, event.getAggregateId(),
                                event.getPayload(), event.getEventType(),
                                event.getAggregateType(), event.getAggregateId(),
                                eventHeaders)
                                .get(SEND_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } else {
                        // Fallback: direct JSON publish (pre-migration behavior)
                        var record = new ProducerRecord<>(topic, null, event.getAggregateId(), event.getPayload());
                        eventHeaders.forEach((k, v) ->
                                record.headers().add(new RecordHeader(k, v.getBytes(StandardCharsets.UTF_8))));

                        kafkaTemplate.send(record)
                                .get(SEND_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    }

                    event.markPublished();

                    // Append to event log after successful publish
                    if (postPublishCallback != null) {
                        postPublishCallback.onPublished(event);
                    }

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
        // Release leader lock — atomic compare-and-delete so we never delete a
        // lock another instance acquired after our TTL elapsed.
        try {
            redisTemplate.execute(RELEASE_IF_OWNER, List.of(LEADER_LOCK_KEY), instanceId());
        } catch (Exception e) {
            log.debug("Could not release leader lock: {}", e.getMessage());
        }
        log.info("Outbox relay shutdown complete");
    }
}
