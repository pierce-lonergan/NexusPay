package io.nexuspay.billing.application.service;

import io.nexuspay.billing.application.port.out.BillingOutboxPort;
import io.nexuspay.billing.application.port.out.InvoiceRepository;
import io.nexuspay.billing.application.port.out.PaymentPort;
import io.nexuspay.billing.application.port.out.SubscriptionRepository;
import io.nexuspay.billing.config.BillingConfig;
import io.nexuspay.billing.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Dunning service for handling failed subscription payments.
 *
 * <p>Implements a configurable retry schedule with smart retry optimization.
 * When dunning is exhausted (all retries failed + grace period expired),
 * the subscription is canceled and the invoice marked uncollectible.</p>
 *
 * <h3>Smart retry features (Sprint 2.5b)</h3>
 * <ul>
 *   <li>Configurable retry schedule via {@code nexuspay.billing.dunning.retry-schedule}</li>
 *   <li>Card-type aware timing (debit retries earlier, credit retries later)</li>
 *   <li>Customer timezone-based optimal hour scheduling</li>
 *   <li>Weekend avoidance (retries shift to Monday)</li>
 *   <li>Outbox event publishing for all dunning lifecycle transitions</li>
 * </ul>
 *
 * @since 0.2.5 (Sprint 2.5a), enhanced 0.2.5b (Sprint 2.5b)
 */
@Service
public class DunningService {

    private static final Logger log = LoggerFactory.getLogger(DunningService.class);

    private final InvoiceRepository invoiceRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PaymentPort paymentPort;
    private final BillingConfig.BillingProperties properties;
    private final SmartRetryOptimizer smartRetryOptimizer;
    private final BillingOutboxPort outboxPort;

    public DunningService(InvoiceRepository invoiceRepository,
                           SubscriptionRepository subscriptionRepository,
                           PaymentPort paymentPort,
                           BillingConfig.BillingProperties properties,
                           SmartRetryOptimizer smartRetryOptimizer,
                           BillingOutboxPort outboxPort) {
        this.invoiceRepository = invoiceRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.paymentPort = paymentPort;
        this.properties = properties;
        this.smartRetryOptimizer = smartRetryOptimizer;
        this.outboxPort = outboxPort;
    }

    /**
     * Initiates the dunning sequence after a payment failure.
     */
    @Transactional
    public void initiateDunning(Subscription subscription, Invoice invoice) {
        subscription.markPastDue();
        subscriptionRepository.save(subscription);

        // Schedule first retry
        scheduleRetry(subscription, invoice, 1);

        outboxPort.publishEvent("Subscription", subscription.getId(),
                "DunningInitiated", Map.of(
                        "subscriptionId", subscription.getId(),
                        "invoiceId", invoice.getId(),
                        "amountDue", invoice.getAmountDue(),
                        "currency", invoice.getCurrency()
                ), subscription.getTenantId());

        log.info("Dunning initiated: subscription={}, invoice={}", subscription.getId(), invoice.getId());
    }

    /**
     * Processes pending dunning attempts that are due.
     *
     * @return number of attempts processed
     */
    @Transactional
    public int processPendingAttempts() {
        List<DunningAttempt> pending = invoiceRepository.findPendingDunning(Instant.now(), 50);

        int processed = 0;
        for (DunningAttempt attempt : pending) {
            try {
                processAttempt(attempt);
                processed++;
            } catch (Exception e) {
                log.error("Failed to process dunning attempt {}: {}",
                        attempt.getId(), e.getMessage(), e);
            }
        }

        if (processed > 0) {
            log.info("Dunning processing complete: {} attempts processed", processed);
        }
        return processed;
    }

    private void processAttempt(DunningAttempt attempt) {
        Subscription sub = subscriptionRepository.findById(attempt.getSubscriptionId()).orElse(null);
        Invoice invoice = invoiceRepository.findById(attempt.getInvoiceId()).orElse(null);

        if (sub == null || invoice == null) {
            attempt.markFailed("Subscription or invoice not found");
            invoiceRepository.saveDunningAttempt(attempt);
            return;
        }

        if (sub.getPaymentMethodId() == null) {
            attempt.markFailed("No payment method on subscription");
            invoiceRepository.saveDunningAttempt(attempt);
            return;
        }

        // Attempt payment
        PaymentPort.PaymentResult result = paymentPort.collectPayment(
                sub.getTenantId(), sub.getCustomerId(),
                sub.getPaymentMethodId(), invoice.getAmountDue(),
                invoice.getCurrency(), "Dunning retry for " + invoice.getId(),
                invoice.getId()
        );

        if (result.success()) {
            attempt.markSuccess(result.paymentId());
            invoice.markPaid(result.paymentId());

            // Recover subscription
            sub.setStatus(SubscriptionState.ACTIVE);
            subscriptionRepository.save(sub);
            invoiceRepository.save(invoice);
            invoiceRepository.saveDunningAttempt(attempt);

            outboxPort.publishEvent("Subscription", sub.getId(),
                    "DunningRecovered", Map.of(
                            "subscriptionId", sub.getId(),
                            "invoiceId", invoice.getId(),
                            "paymentId", result.paymentId(),
                            "attemptNumber", attempt.getAttemptNumber()
                    ), sub.getTenantId());

            log.info("Dunning success: subscription={}, attempt={}", sub.getId(), attempt.getAttemptNumber());
        } else {
            attempt.markFailed(result.failureReason());
            invoiceRepository.saveDunningAttempt(attempt);

            int[] retrySchedule = properties.getDunning().getRetrySchedule();

            // Schedule next retry or cancel
            if (attempt.getAttemptNumber() < retrySchedule.length) {
                scheduleRetry(sub, invoice, attempt.getAttemptNumber() + 1);

                outboxPort.publishEvent("Subscription", sub.getId(),
                        "DunningRetryFailed", Map.of(
                                "subscriptionId", sub.getId(),
                                "invoiceId", invoice.getId(),
                                "attemptNumber", attempt.getAttemptNumber(),
                                "failureReason", result.failureReason() != null ? result.failureReason() : "unknown",
                                "nextAttempt", attempt.getAttemptNumber() + 1
                        ), sub.getTenantId());
            } else {
                // Dunning exhausted — cancel after grace period
                log.warn("Dunning exhausted: subscription={}, canceling", sub.getId());
                sub.cancelDueToNonPayment();
                subscriptionRepository.save(sub);
                invoice.markUncollectible();
                invoiceRepository.save(invoice);

                outboxPort.publishEvent("Subscription", sub.getId(),
                        "DunningExhausted", Map.of(
                                "subscriptionId", sub.getId(),
                                "invoiceId", invoice.getId(),
                                "totalAttempts", attempt.getAttemptNumber()
                        ), sub.getTenantId());

                outboxPort.publishEvent("Subscription", sub.getId(),
                        "SubscriptionCanceled", Map.of(
                                "subscriptionId", sub.getId(),
                                "reason", "dunning_exhausted"
                        ), sub.getTenantId());
            }
        }
    }

    private void scheduleRetry(Subscription subscription, Invoice invoice, int attemptNumber) {
        int[] retrySchedule = properties.getDunning().getRetrySchedule();
        int daysDelay = attemptNumber <= retrySchedule.length
                ? retrySchedule[attemptNumber - 1]
                : retrySchedule[retrySchedule.length - 1] + properties.getDunning().getGracePeriodDays();

        Instant baseScheduledAt = Instant.now().plus(daysDelay, ChronoUnit.DAYS);

        // Apply smart retry optimization
        String customerTimezone = extractCustomerTimezone(subscription);
        String cardType = extractCardType(subscription);
        Instant optimizedScheduledAt = smartRetryOptimizer.optimize(baseScheduledAt, customerTimezone, cardType);

        DunningAttempt attempt = DunningAttempt.schedule(
                subscription.getId(), invoice.getId(), subscription.getTenantId(),
                attemptNumber, optimizedScheduledAt
        );

        invoiceRepository.saveDunningAttempt(attempt);
        log.info("Dunning retry scheduled: subscription={}, attempt={}, scheduledAt={} (base={}d, smart={})",
                subscription.getId(), attemptNumber, optimizedScheduledAt, daysDelay,
                properties.getDunning().isSmartRetryEnabled());
    }

    /**
     * Extracts customer timezone from subscription metadata.
     */
    private String extractCustomerTimezone(Subscription subscription) {
        if (subscription.getMetadata() == null) return null;
        Object tz = subscription.getMetadata().get("customer_timezone");
        return tz != null ? tz.toString() : null;
    }

    /**
     * Extracts card type from subscription metadata.
     */
    private String extractCardType(Subscription subscription) {
        if (subscription.getMetadata() == null) return null;
        Object ct = subscription.getMetadata().get("card_type");
        return ct != null ? ct.toString() : null;
    }
}
