package io.nexuspay.ledger.adapter.in.event;

import io.nexuspay.common.event.EventTypes;
import io.nexuspay.common.event.Topics;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand.PostingLine;
import io.nexuspay.ledger.application.EnsureAccountsExistUseCase;
import io.nexuspay.ledger.application.port.JournalEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Consumes payment events from Kafka and creates corresponding ledger entries.
 * Handles PaymentCaptured and RefundCompleted events.
 */
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final CreateJournalEntryUseCase createJournalEntryUseCase;
    private final EnsureAccountsExistUseCase ensureAccountsExistUseCase;
    private final JournalEntryRepository journalEntryRepository;

    public PaymentEventConsumer(CreateJournalEntryUseCase createJournalEntryUseCase,
                                 EnsureAccountsExistUseCase ensureAccountsExistUseCase,
                                 JournalEntryRepository journalEntryRepository) {
        this.createJournalEntryUseCase = createJournalEntryUseCase;
        this.ensureAccountsExistUseCase = ensureAccountsExistUseCase;
        this.journalEntryRepository = journalEntryRepository;
    }

    @KafkaListener(topics = Topics.PAYMENTS, groupId = Topics.LEDGER_CONSUMER_GROUP)
    public void onPaymentEvent(Map<String, Object> event) {
        String eventType = (String) event.get("event_type");
        String aggregateId = (String) event.get("aggregate_id");

        log.debug("Received payment event: type={}, aggregateId={}", eventType, aggregateId);

        try {
            switch (eventType) {
                case EventTypes.PAYMENT_CAPTURED -> handlePaymentCaptured(event);
                case EventTypes.REFUND_COMPLETED -> handleRefundCompleted(event);
                default -> log.debug("Ignoring event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process payment event: type={}, aggregateId={}",
                    eventType, aggregateId, e);
            throw e; // Let DefaultErrorHandler retry, then DLT
        }
    }

    @SuppressWarnings("unchecked")
    private void handlePaymentCaptured(Map<String, Object> event) {
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        String paymentId = (String) event.get("aggregate_id");
        long amount = ((Number) payload.get("amount")).longValue();
        String currency = ((String) payload.get("currency")).toUpperCase();

        // Idempotency: check if journal entry already exists for this payment capture
        if (journalEntryRepository.existsByPaymentReferenceAndDescription(paymentId, "Payment captured")) {
            log.info("Journal entry already exists for payment capture: {}", paymentId);
            return;
        }

        String tenantId = tenantOf(event);

        // Ensure accounts exist for this currency
        ensureAccountsExistUseCase.ensureAccountsForCurrency(currency, tenantId);

        String merchantRecvAccount = EnsureAccountsExistUseCase.merchantReceivablesId(currency);
        String customerLiabAccount = EnsureAccountsExistUseCase.customerLiabilityId(currency);

        // DR Merchant Receivables (asset increases)
        // CR Customer Liability (liability increases)
        var command = new CreateJournalEntryCommand(
                paymentId,
                "Payment captured",
                tenantId,
                Map.of("event_id", event.get("event_id")),
                List.of(
                        new PostingLine(merchantRecvAccount, amount, currency),
                        new PostingLine(customerLiabAccount, -amount, currency)
                )
        );

        var entry = createJournalEntryUseCase.execute(command);
        log.info("Created ledger entry {} for payment capture: {} {} {}",
                entry.getId(), paymentId, amount, currency);
    }

    @SuppressWarnings("unchecked")
    private void handleRefundCompleted(Map<String, Object> event) {
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        String paymentId = (String) payload.get("payment_id");
        String refundId = (String) event.get("aggregate_id");
        long amount = ((Number) payload.get("amount")).longValue();
        String currency = ((String) payload.get("currency")).toUpperCase();

        // Idempotency: check if journal entry already exists for this refund
        if (journalEntryRepository.existsByPaymentReferenceAndDescription(refundId, "Refund completed")) {
            log.info("Journal entry already exists for refund: {}", refundId);
            return;
        }

        String tenantId = tenantOf(event);

        // Ensure accounts exist for this currency
        ensureAccountsExistUseCase.ensureAccountsForCurrency(currency, tenantId);

        String refundsAccount = EnsureAccountsExistUseCase.refundsId(currency);
        String merchantRecvAccount = EnsureAccountsExistUseCase.merchantReceivablesId(currency);

        // DR Refunds (expense increases)
        // CR Merchant Receivables (asset decreases)
        var command = new CreateJournalEntryCommand(
                refundId,
                "Refund completed",
                tenantId,
                Map.of("event_id", event.get("event_id"), "payment_id", paymentId),
                List.of(
                        new PostingLine(refundsAccount, amount, currency),
                        new PostingLine(merchantRecvAccount, -amount, currency)
                )
        );

        var entry = createJournalEntryUseCase.execute(command);
        log.info("Created ledger entry {} for refund: {} {} {}",
                entry.getId(), refundId, amount, currency);
    }

    /**
     * Resolves the tenant from the event envelope (top-level or payload),
     * falling back to the default tenant for legacy events that carry none.
     */
    @SuppressWarnings("unchecked")
    private static String tenantOf(Map<String, Object> event) {
        Object tenant = event.get("tenant_id");
        if (tenant == null && event.get("payload") instanceof Map<?, ?> payload) {
            tenant = ((Map<String, Object>) payload).get("tenant_id");
        }
        return tenant instanceof String s && !s.isBlank() ? s : EnsureAccountsExistUseCase.DEFAULT_TENANT;
    }
}
