package io.nexuspay.billing.application.service;

import io.nexuspay.billing.application.port.out.InvoiceRepository;
import io.nexuspay.billing.application.port.out.PaymentPort;
import io.nexuspay.billing.application.port.out.SubscriptionRepository;
import io.nexuspay.billing.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Dunning service for handling failed subscription payments.
 *
 * <p>Implements a configurable retry schedule with escalation. When dunning
 * is exhausted, the subscription is canceled.</p>
 *
 * <h3>Default retry schedule</h3>
 * <ul>
 *   <li>Attempt 1: 1 day after initial failure</li>
 *   <li>Attempt 2: 3 days after initial failure</li>
 *   <li>Attempt 3: 5 days after initial failure</li>
 *   <li>Attempt 4: 7 days after initial failure</li>
 *   <li>Grace period: 3 days after last attempt</li>
 *   <li>Cancellation: after grace period</li>
 * </ul>
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
@Service
public class DunningService {

    private static final Logger log = LoggerFactory.getLogger(DunningService.class);

    /** Retry schedule: days after initial failure for each attempt. */
    private static final int[] DEFAULT_RETRY_SCHEDULE = {1, 3, 5, 7};

    /** Grace period days after last retry before cancellation. */
    private static final int GRACE_PERIOD_DAYS = 3;

    private final InvoiceRepository invoiceRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PaymentPort paymentPort;

    public DunningService(InvoiceRepository invoiceRepository,
                           SubscriptionRepository subscriptionRepository,
                           PaymentPort paymentPort) {
        this.invoiceRepository = invoiceRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.paymentPort = paymentPort;
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

            log.info("Dunning success: subscription={}, attempt={}", sub.getId(), attempt.getAttemptNumber());
        } else {
            attempt.markFailed(result.failureReason());
            invoiceRepository.saveDunningAttempt(attempt);

            // Schedule next retry or cancel
            if (attempt.getAttemptNumber() < DEFAULT_RETRY_SCHEDULE.length) {
                scheduleRetry(sub, invoice, attempt.getAttemptNumber() + 1);
            } else {
                // Dunning exhausted — cancel after grace period
                log.warn("Dunning exhausted: subscription={}, canceling", sub.getId());
                sub.cancelDueToNonPayment();
                subscriptionRepository.save(sub);
                invoice.markUncollectible();
                invoiceRepository.save(invoice);
            }
        }
    }

    private void scheduleRetry(Subscription subscription, Invoice invoice, int attemptNumber) {
        int daysDelay = attemptNumber <= DEFAULT_RETRY_SCHEDULE.length
                ? DEFAULT_RETRY_SCHEDULE[attemptNumber - 1]
                : DEFAULT_RETRY_SCHEDULE[DEFAULT_RETRY_SCHEDULE.length - 1] + GRACE_PERIOD_DAYS;

        DunningAttempt attempt = DunningAttempt.schedule(
                subscription.getId(), invoice.getId(), subscription.getTenantId(),
                attemptNumber, Instant.now().plus(daysDelay, ChronoUnit.DAYS)
        );

        invoiceRepository.saveDunningAttempt(attempt);
        log.info("Dunning retry scheduled: subscription={}, attempt={}, scheduledIn={}d",
                subscription.getId(), attemptNumber, daysDelay);
    }
}
