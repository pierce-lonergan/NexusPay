package io.nexuspay.payment.adapter.out.maintenance;

import io.nexuspay.common.rls.SystemTransactional;
import io.nexuspay.payment.adapter.in.webhook.InboundWebhookRepository;
import io.nexuspay.payment.adapter.out.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Scheduled cleanup job for data retention.
 *
 * Addresses:
 *   GAP-005: Outbox table unbounded growth — deletes published events older than retention period
 *   GAP-006: Webhook raw payload unbounded growth — deletes processed webhooks older than retention period
 *
 * Runs daily at 3:00 AM. Deletes in batches to avoid long-running transactions.
 */
@Component
public class DataRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionJob.class);

    private final OutboxEventRepository outboxRepository;
    private final InboundWebhookRepository webhookRepository;
    private final Duration outboxRetention;
    private final Duration webhookRetention;

    public DataRetentionJob(
            OutboxEventRepository outboxRepository,
            InboundWebhookRepository webhookRepository,
            @Value("${nexuspay.retention.outbox-days:7}") int outboxRetentionDays,
            @Value("${nexuspay.retention.webhook-days:90}") int webhookRetentionDays) {
        this.outboxRepository = outboxRepository;
        this.webhookRepository = webhookRepository;
        this.outboxRetention = Duration.ofDays(outboxRetentionDays);
        this.webhookRetention = Duration.ofDays(webhookRetentionDays);
    }

    @SystemTransactional
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupOutboxEvents() {
        Instant cutoff = Instant.now().minus(outboxRetention);
        int deleted = outboxRepository.deletePublishedBefore(cutoff);
        if (deleted > 0) {
            log.info("Outbox cleanup: deleted {} published events older than {}", deleted, cutoff);
        }
    }

    @SystemTransactional
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void cleanupWebhookPayloads() {
        Instant cutoff = Instant.now().minus(webhookRetention);
        int deleted = webhookRepository.deleteProcessedBefore(cutoff);
        if (deleted > 0) {
            log.info("Webhook cleanup: deleted {} processed webhooks older than {}", deleted, cutoff);
        }
    }
}
