package io.nexuspay.billing.adapter.in.scheduler;

import io.nexuspay.billing.application.port.out.BillingOutboxPort;
import io.nexuspay.billing.application.port.out.ProductRepository;
import io.nexuspay.billing.application.port.out.SubscriptionRepository;
import io.nexuspay.billing.application.service.DunningService;
import io.nexuspay.billing.application.service.InvoiceGenerationService;
import io.nexuspay.billing.domain.Invoice;
import io.nexuspay.billing.domain.Price;
import io.nexuspay.billing.domain.Subscription;
import io.nexuspay.common.rls.SystemTransactional;
import io.nexuspay.common.rls.TenantWorkRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Daily scheduler that finds subscriptions due for renewal,
 * generates invoices, and collects payment.
 *
 * @since 0.2.5 (Sprint 2.5a), enhanced 0.2.5b (Sprint 2.5b)
 */
@Component
public class RenewalScheduler {

    private static final Logger log = LoggerFactory.getLogger(RenewalScheduler.class);

    private final SubscriptionRepository subscriptionRepository;
    private final ProductRepository productRepository;
    private final InvoiceGenerationService invoiceService;
    private final DunningService dunningService;
    private final BillingOutboxPort outboxPort;
    private final SchedulerLock schedulerLock;
    private final TenantWorkRunner tenantWork;

    public RenewalScheduler(SubscriptionRepository subscriptionRepository,
                             ProductRepository productRepository,
                             InvoiceGenerationService invoiceService,
                             DunningService dunningService,
                             BillingOutboxPort outboxPort,
                             SchedulerLock schedulerLock,
                             TenantWorkRunner tenantWork) {
        this.subscriptionRepository = subscriptionRepository;
        this.productRepository = productRepository;
        this.invoiceService = invoiceService;
        this.dunningService = dunningService;
        this.outboxPort = outboxPort;
        this.schedulerLock = schedulerLock;
        this.tenantWork = tenantWork;
    }

    /**
     * Runs daily at 2:00 AM — processes subscription renewals.
     * Guarded by a cross-instance lock so only one replica charges per cycle (B-001).
     */
    @SystemTransactional
    @Scheduled(cron = "0 0 2 * * *")
    public void processRenewals() {
        schedulerLock.runExclusively("renewals", Duration.ofHours(1), this::doProcessRenewals);
    }

    void doProcessRenewals() {
        log.info("Starting subscription renewal cycle");

        // Cross-tenant DISCOVERY runs under the scheduler's SYSTEM pin (BYPASSRLS) so it sees every
        // tenant's due subscriptions. Each subscription's invoice + charge + save + outbox then runs in
        // its OWN transaction bound to that subscription's tenant on the APP role (B-002), so RLS
        // WITH CHECK guards the per-tenant writes. One tenant's failure no longer rolls back the others.
        List<Subscription> due = subscriptionRepository.findDueForRenewal(Instant.now(), 500);
        int renewed = 0;
        int failed = 0;

        for (Subscription sub : due) {
            try {
                Boolean result = tenantWork.callInTenant(sub.getTenantId(), () -> processOneRenewal(sub));
                if (result == null) {
                    continue; // price missing → skipped, not a failure
                }
                if (result) {
                    renewed++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                log.error("Renewal failed for subscription {}: {}", sub.getId(), e.getMessage(), e);
                failed++;
            }
        }

        log.info("Renewal cycle complete: processed={}, renewed={}, failed={}",
                due.size(), renewed, failed);
    }

    /**
     * Processes one subscription's renewal inside the caller-provided (tenant-bound) transaction.
     * Returns {@code TRUE} if renewed, {@code FALSE} if payment failed and dunning was initiated,
     * {@code null} if the price was missing and the subscription was skipped.
     */
    private Boolean processOneRenewal(Subscription sub) {
        Price price = productRepository.findPriceById(sub.getPriceId()).orElse(null);
        if (price == null) {
            log.warn("Price not found for subscription {}, skipping", sub.getId());
            return null;
        }

        // Generate invoice for next period
        Invoice invoice = invoiceService.generateInvoice(sub, price);

        // Attempt payment
        boolean paid = invoiceService.collectPayment(invoice, sub.getPaymentMethodId());

        if (paid) {
            sub.renew(price);
            subscriptionRepository.save(sub);

            outboxPort.publishEvent("Subscription", sub.getId(),
                    "SubscriptionRenewed", Map.of(
                            "subscriptionId", sub.getId(),
                            "invoiceId", invoice.getId(),
                            "newPeriodStart", sub.getCurrentPeriodStart().toString(),
                            "newPeriodEnd", sub.getCurrentPeriodEnd().toString()
                    ), sub.getTenantId());

            return Boolean.TRUE;
        }
        // Payment failed — initiate dunning
        dunningService.initiateDunning(sub, invoice);
        return Boolean.FALSE;
    }

    /**
     * Runs every 4 hours — processes pending dunning retries.
     * Guarded by a cross-instance lock so only one replica retries per cycle (B-001).
     */
    @SystemTransactional
    @Scheduled(cron = "0 0 */4 * * *")
    public void processDunning() {
        schedulerLock.runExclusively("dunning", Duration.ofHours(1), () -> {
            int processed = dunningService.processPendingAttempts();
            if (processed > 0) {
                log.info("Dunning cycle complete: {} attempts processed", processed);
            }
        });
    }
}
