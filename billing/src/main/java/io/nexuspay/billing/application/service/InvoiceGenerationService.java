package io.nexuspay.billing.application.service;

import io.nexuspay.billing.application.port.out.BillingOutboxPort;
import io.nexuspay.billing.application.port.out.InvoiceRepository;
import io.nexuspay.billing.application.port.out.PaymentPort;
import io.nexuspay.billing.domain.*;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.common.tenant.TenantOwnership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generates invoices for subscriptions and handles payment collection.
 *
 * <p>All invoice lifecycle transitions publish outbox events for async
 * downstream consumption via the {@code nexuspay.billing} Kafka topic.</p>
 *
 * @since 0.2.5 (Sprint 2.5a), enhanced 0.2.5b (Sprint 2.5b)
 */
@Service
public class InvoiceGenerationService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceGenerationService.class);

    private final InvoiceRepository invoiceRepository;
    private final PaymentPort paymentPort;
    private final BillingOutboxPort outboxPort;

    public InvoiceGenerationService(InvoiceRepository invoiceRepository,
                                     PaymentPort paymentPort,
                                     BillingOutboxPort outboxPort) {
        this.invoiceRepository = invoiceRepository;
        this.paymentPort = paymentPort;
        this.outboxPort = outboxPort;
    }

    /**
     * Generates an invoice for the current billing period.
     */
    @Transactional
    public Invoice generateInvoice(Subscription subscription, Price price) {
        long amount = price.calculateAmount(subscription.getQuantity());

        Invoice invoice = Invoice.createForSubscription(
                subscription.getTenantId(), subscription.getId(),
                subscription.getCustomerId(), price.getCurrency(),
                subscription.getCurrentPeriodStart(), subscription.getCurrentPeriodEnd()
        );

        InvoiceLineItem lineItem = new InvoiceLineItem(
                PrefixedId.invoiceLineItem(), invoice.getId(), subscription.getTenantId(),
                "Subscription charge", amount, price.getCurrency(),
                subscription.getQuantity(), false,
                subscription.getCurrentPeriodStart(), subscription.getCurrentPeriodEnd()
        );

        invoice.addLineItem(lineItem);
        invoice.finalise();

        invoice = invoiceRepository.save(invoice);
        invoiceRepository.saveLineItem(lineItem);

        outboxPort.publishEvent("Invoice", invoice.getId(),
                "InvoiceCreated", Map.of(
                        "invoiceId", invoice.getId(),
                        "subscriptionId", subscription.getId(),
                        "customerId", subscription.getCustomerId(),
                        "total", invoice.getTotal(),
                        "currency", invoice.getCurrency(),
                        "status", invoice.getStatus().name()
                ), subscription.getTenantId());

        log.info("Invoice generated: id={}, subscription={}, total={}",
                invoice.getId(), subscription.getId(), invoice.getTotal());

        return invoice;
    }

    /**
     * Attempts to collect payment on an open invoice.
     */
    @Transactional
    public boolean collectPayment(Invoice invoice, String paymentMethodId) {
        if (invoice.getStatus() != InvoiceStatus.OPEN) {
            log.warn("Cannot collect on invoice {} with status {}", invoice.getId(), invoice.getStatus());
            return false;
        }

        PaymentPort.PaymentResult result = paymentPort.collectPayment(
                invoice.getTenantId(), invoice.getCustomerId(),
                paymentMethodId, invoice.getAmountDue(),
                invoice.getCurrency(),
                "Invoice " + invoice.getId(),
                invoice.getId()
        );

        if (result.success()) {
            invoice.markPaid(result.paymentId());
            invoiceRepository.save(invoice);

            outboxPort.publishEvent("Invoice", invoice.getId(),
                    "InvoicePaid", Map.of(
                            "invoiceId", invoice.getId(),
                            "paymentId", result.paymentId(),
                            "amount", invoice.getTotal(),
                            "currency", invoice.getCurrency()
                    ), invoice.getTenantId());

            log.info("Invoice paid: id={}, paymentId={}", invoice.getId(), result.paymentId());
            return true;
        } else {
            log.warn("Invoice payment failed: id={}, reason={}", invoice.getId(), result.failureReason());
            return false;
        }
    }

    // -- Query methods --

    /**
     * SEC-26: tenant-scoped by-id lookup. Returns the invoice only when it belongs to {@code tenantId};
     * an absent OR foreign-tenant invoice yields an empty Optional (no cross-tenant existence oracle).
     */
    public Optional<Invoice> findById(String id, String tenantId) {
        return invoiceRepository.findByIdAndTenantId(id, tenantId);
    }

    /**
     * SEC-26: tenant-scoped fetch-or-404. Mirrors the SEC-23 {@code getAssessment} idiom — pairs a
     * tenant-scoped finder with {@link TenantOwnership#require} so a tenant-A caller cannot fetch a
     * tenant-B invoice by id.
     */
    public Invoice getOwnedInvoice(String id, String tenantId) {
        return TenantOwnership.require(invoiceRepository.findByIdAndTenantId(id, tenantId), "Invoice");
    }

    public List<Invoice> listByTenant(String tenantId, int limit, int offset) {
        return invoiceRepository.findByTenant(tenantId, limit, offset);
    }

    /**
     * SEC-26: line items are reachable only through their tenant-scoped parent invoice. Asserting
     * invoice ownership first (404 on absent/foreign) prevents reading another tenant's line items by
     * passing a foreign invoice id.
     */
    public List<InvoiceLineItem> getLineItems(String invoiceId, String tenantId) {
        getOwnedInvoice(invoiceId, tenantId); // throws ResourceNotFoundException (404) if not owned
        return invoiceRepository.findLineItemsByInvoice(invoiceId);
    }
}
