package io.nexuspay.billing.application.service;

import io.nexuspay.billing.application.port.out.BillingOutboxPort;
import io.nexuspay.billing.application.port.out.ProductRepository;
import io.nexuspay.billing.application.port.out.SubscriptionRepository;
import io.nexuspay.billing.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Core service managing subscription lifecycle operations.
 *
 * <p>All state transitions publish outbox events for async downstream
 * consumption via the {@code nexuspay.billing} Kafka topic.</p>
 *
 * @since 0.2.5 (Sprint 2.5a), enhanced 0.2.5b (Sprint 2.5b)
 */
@Service
public class SubscriptionLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionLifecycleService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final ProductRepository productRepository;
    private final InvoiceGenerationService invoiceService;
    private final BillingOutboxPort outboxPort;

    public SubscriptionLifecycleService(SubscriptionRepository subscriptionRepository,
                                         ProductRepository productRepository,
                                         InvoiceGenerationService invoiceService,
                                         BillingOutboxPort outboxPort) {
        this.subscriptionRepository = subscriptionRepository;
        this.productRepository = productRepository;
        this.invoiceService = invoiceService;
        this.outboxPort = outboxPort;
    }

    /**
     * Creates a new subscription from a price.
     */
    @Transactional
    public Subscription createSubscription(String tenantId, String customerId,
                                            String priceId, int quantity,
                                            String paymentMethodId,
                                            Map<String, Object> metadata) {

        Price price = productRepository.findPriceById(priceId)
                .orElseThrow(() -> new IllegalArgumentException("Price not found: " + priceId));

        Subscription sub = Subscription.create(tenantId, customerId, price, quantity,
                paymentMethodId, metadata);

        sub = subscriptionRepository.save(sub);

        // If no trial, generate first invoice immediately
        if (sub.getStatus() == SubscriptionState.ACTIVE) {
            invoiceService.generateInvoice(sub, price);
        }

        outboxPort.publishEvent("Subscription", sub.getId(),
                "SubscriptionCreated", Map.of(
                        "subscriptionId", sub.getId(),
                        "customerId", customerId,
                        "priceId", priceId,
                        "quantity", quantity,
                        "status", sub.getStatus().name(),
                        "trialEnd", sub.getTrialEnd() != null ? sub.getTrialEnd().toString() : "none"
                ), tenantId);

        log.info("Subscription created: id={}, customer={}, status={}, price={}",
                sub.getId(), customerId, sub.getStatus(), priceId);

        return sub;
    }

    /**
     * Cancels a subscription immediately or at period end.
     */
    @Transactional
    public Subscription cancel(String subscriptionId, boolean atPeriodEnd) {
        Subscription sub = getOrThrow(subscriptionId);
        sub.cancel(atPeriodEnd);
        sub = subscriptionRepository.save(sub);

        outboxPort.publishEvent("Subscription", sub.getId(),
                "SubscriptionCanceled", Map.of(
                        "subscriptionId", subscriptionId,
                        "atPeriodEnd", atPeriodEnd,
                        "reason", "user_requested"
                ), sub.getTenantId());

        log.info("Subscription canceled: id={}, atPeriodEnd={}", subscriptionId, atPeriodEnd);
        return sub;
    }

    /**
     * Pauses a subscription (admin action).
     */
    @Transactional
    public Subscription pause(String subscriptionId) {
        Subscription sub = getOrThrow(subscriptionId);
        sub.pause();
        sub = subscriptionRepository.save(sub);

        outboxPort.publishEvent("Subscription", sub.getId(),
                "SubscriptionPaused", Map.of(
                        "subscriptionId", subscriptionId
                ), sub.getTenantId());

        log.info("Subscription paused: id={}", subscriptionId);
        return sub;
    }

    /**
     * Resumes a paused subscription.
     */
    @Transactional
    public Subscription resume(String subscriptionId) {
        Subscription sub = getOrThrow(subscriptionId);
        Price price = productRepository.findPriceById(sub.getPriceId())
                .orElseThrow(() -> new IllegalArgumentException("Price not found: " + sub.getPriceId()));
        sub.resume(price);
        sub = subscriptionRepository.save(sub);

        outboxPort.publishEvent("Subscription", sub.getId(),
                "SubscriptionResumed", Map.of(
                        "subscriptionId", subscriptionId,
                        "newPeriodEnd", sub.getCurrentPeriodEnd().toString()
                ), sub.getTenantId());

        log.info("Subscription resumed: id={}", subscriptionId);
        return sub;
    }

    /**
     * Changes the subscription to a new price (upgrade/downgrade).
     */
    @Transactional
    public Subscription changePlan(String subscriptionId, String newPriceId) {
        Subscription sub = getOrThrow(subscriptionId);
        String oldPriceId = sub.getPriceId();
        Price newPrice = productRepository.findPriceById(newPriceId)
                .orElseThrow(() -> new IllegalArgumentException("Price not found: " + newPriceId));

        sub.setPriceId(newPriceId);
        sub = subscriptionRepository.save(sub);

        outboxPort.publishEvent("Subscription", sub.getId(),
                "SubscriptionPlanChanged", Map.of(
                        "subscriptionId", subscriptionId,
                        "oldPriceId", oldPriceId,
                        "newPriceId", newPriceId
                ), sub.getTenantId());

        log.info("Subscription plan changed: id={}, newPrice={}", subscriptionId, newPriceId);
        return sub;
    }

    // -- Query methods --

    public Optional<Subscription> findById(String id) {
        return subscriptionRepository.findById(id);
    }

    public List<Subscription> listByTenant(String tenantId, int limit, int offset) {
        return subscriptionRepository.findByTenant(tenantId, limit, offset);
    }

    // -- Helpers --

    private Subscription getOrThrow(String id) {
        return subscriptionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + id));
    }
}
