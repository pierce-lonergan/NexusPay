package io.nexuspay.billing.application.service;

import io.nexuspay.billing.application.port.out.BillingOutboxPort;
import io.nexuspay.billing.application.port.out.ProductRepository;
import io.nexuspay.billing.application.port.out.SubscriptionRepository;
import io.nexuspay.billing.domain.Price;
import io.nexuspay.billing.domain.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Manages trial periods and automatic conversion to paid subscriptions.
 *
 * @since 0.2.5 (Sprint 2.5a), enhanced 0.2.5b (Sprint 2.5b)
 */
@Service
public class TrialManagementService {

    private static final Logger log = LoggerFactory.getLogger(TrialManagementService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final ProductRepository productRepository;
    private final InvoiceGenerationService invoiceService;
    private final BillingOutboxPort outboxPort;

    public TrialManagementService(SubscriptionRepository subscriptionRepository,
                                   ProductRepository productRepository,
                                   InvoiceGenerationService invoiceService,
                                   BillingOutboxPort outboxPort) {
        this.subscriptionRepository = subscriptionRepository;
        this.productRepository = productRepository;
        this.invoiceService = invoiceService;
        this.outboxPort = outboxPort;
    }

    /**
     * Converts expired trials to active subscriptions and generates first invoice.
     *
     * @return number of subscriptions converted
     */
    @Transactional
    public int convertExpiredTrials() {
        List<Subscription> expiredTrials = subscriptionRepository.findExpiredTrials(Instant.now(), 100);

        int converted = 0;
        for (Subscription sub : expiredTrials) {
            try {
                Price price = productRepository.findPriceById(sub.getPriceId()).orElse(null);
                if (price == null) {
                    log.warn("Price not found for subscription {}, skipping trial conversion", sub.getId());
                    continue;
                }

                sub.activate(price);
                subscriptionRepository.save(sub);

                // Generate first invoice
                invoiceService.generateInvoice(sub, price);

                outboxPort.publishEvent("Subscription", sub.getId(),
                        "SubscriptionTrialConverted", Map.of(
                                "subscriptionId", sub.getId(),
                                "customerId", sub.getCustomerId(),
                                "priceId", sub.getPriceId(),
                                "newPeriodEnd", sub.getCurrentPeriodEnd().toString()
                        ), sub.getTenantId());

                converted++;
                log.info("Trial converted to active: subscription={}", sub.getId());
            } catch (Exception e) {
                log.error("Failed to convert trial for subscription {}: {}",
                        sub.getId(), e.getMessage(), e);
            }
        }

        if (converted > 0) {
            log.info("Trial conversion complete: {} subscriptions converted", converted);
        }
        return converted;
    }
}
