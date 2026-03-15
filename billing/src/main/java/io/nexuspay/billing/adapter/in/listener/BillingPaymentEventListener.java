package io.nexuspay.billing.adapter.in.listener;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.billing.application.port.out.InvoiceRepository;
import io.nexuspay.billing.application.port.out.SubscriptionRepository;
import io.nexuspay.billing.application.service.DunningService;
import io.nexuspay.billing.domain.Invoice;
import io.nexuspay.billing.domain.InvoiceStatus;
import io.nexuspay.billing.domain.Subscription;
import io.nexuspay.billing.domain.SubscriptionState;
import io.nexuspay.common.event.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Kafka consumer that listens for payment lifecycle events on the
 * {@code nexuspay.payments} topic and updates billing state accordingly.
 *
 * <p>Handles async payment results from HyperSwitch webhooks that flow
 * through the outbox relay. This enables the billing module to react to
 * payment outcomes without polling:</p>
 * <ul>
 *   <li>{@code PaymentCaptured} — marks the invoice as paid, recovers
 *       subscription from PAST_DUE if applicable</li>
 *   <li>{@code PaymentFailed} — initiates or continues dunning if the
 *       invoice is linked to a subscription</li>
 * </ul>
 *
 * @since 0.2.5b (Sprint 2.5b)
 */
@Component
public class BillingPaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(BillingPaymentEventListener.class);

    private final InvoiceRepository invoiceRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final DunningService dunningService;
    private final ObjectMapper objectMapper;

    public BillingPaymentEventListener(InvoiceRepository invoiceRepository,
                                        SubscriptionRepository subscriptionRepository,
                                        DunningService dunningService,
                                        ObjectMapper objectMapper) {
        this.invoiceRepository = invoiceRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.dunningService = dunningService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = Topics.PAYMENTS,
            groupId = Topics.BILLING_CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        String eventType = extractHeader(record, "event_type");
        if (eventType == null) {
            log.warn("Payment event missing event_type header, skipping");
            return;
        }

        switch (eventType) {
            case "PaymentCaptured" -> handlePaymentCaptured(record);
            case "PaymentFailed" -> handlePaymentFailed(record);
            default -> log.debug("Ignoring payment event type: {}", eventType);
        }
    }

    private void handlePaymentCaptured(ConsumerRecord<String, String> record) {
        try {
            Map<String, Object> payload = parsePayload(record.value());
            Map<String, Object> eventPayload = extractNestedPayload(payload);

            String invoiceId = getString(eventPayload, "invoice_id");
            String paymentId = getString(eventPayload, "gateway_payment_id");

            if (invoiceId == null) {
                // Not a billing-related payment
                return;
            }

            Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
            if (invoice == null) {
                log.debug("Invoice {} not found for captured payment, may not be billing-related", invoiceId);
                return;
            }

            if (invoice.getStatus() == InvoiceStatus.PAID) {
                log.debug("Invoice {} already paid, skipping duplicate event", invoiceId);
                return;
            }

            invoice.markPaid(paymentId);
            invoiceRepository.save(invoice);

            // If subscription is PAST_DUE, recover it
            if (invoice.getSubscriptionId() != null) {
                Subscription sub = subscriptionRepository.findById(invoice.getSubscriptionId()).orElse(null);
                if (sub != null && sub.getStatus() == SubscriptionState.PAST_DUE) {
                    sub.setStatus(SubscriptionState.ACTIVE);
                    sub.setUpdatedAt(java.time.Instant.now());
                    subscriptionRepository.save(sub);
                    log.info("Subscription {} recovered from PAST_DUE via async payment capture", sub.getId());
                }
            }

            log.info("Async payment captured processed: invoice={}, paymentId={}", invoiceId, paymentId);

        } catch (Exception e) {
            log.error("Error processing PaymentCaptured event: {}", e.getMessage(), e);
            throw e; // Let error handler route to DLT
        }
    }

    private void handlePaymentFailed(ConsumerRecord<String, String> record) {
        try {
            Map<String, Object> payload = parsePayload(record.value());
            Map<String, Object> eventPayload = extractNestedPayload(payload);

            String invoiceId = getString(eventPayload, "invoice_id");
            if (invoiceId == null) {
                return; // Not billing-related
            }

            Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
            if (invoice == null || invoice.getSubscriptionId() == null) {
                return;
            }

            Subscription sub = subscriptionRepository.findById(invoice.getSubscriptionId()).orElse(null);
            if (sub == null) {
                return;
            }

            // Only initiate dunning if subscription is ACTIVE (first failure)
            // If already PAST_DUE, the dunning scheduler handles retries
            if (sub.getStatus() == SubscriptionState.ACTIVE) {
                dunningService.initiateDunning(sub, invoice);
                log.info("Dunning initiated via async payment failure: subscription={}, invoice={}",
                        sub.getId(), invoiceId);
            }

        } catch (Exception e) {
            log.error("Error processing PaymentFailed event: {}", e.getMessage(), e);
            throw e;
        }
    }

    // -- Helpers --

    private String extractHeader(ConsumerRecord<String, String> record, String key) {
        var header = record.headers().lastHeader(key);
        if (header == null) return null;
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to parse event payload: {}", e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractNestedPayload(Map<String, Object> envelope) {
        Object nested = envelope.get("payload");
        if (nested instanceof Map) {
            return (Map<String, Object>) nested;
        }
        // Flat structure — return as-is
        return envelope;
    }

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
