package io.nexuspay.billing.application.service;

import io.nexuspay.billing.application.port.out.InvoiceRepository;
import io.nexuspay.billing.application.port.out.PaymentPort;
import io.nexuspay.billing.domain.*;
import io.nexuspay.common.id.PrefixedId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Generates invoices for subscriptions and handles payment collection.
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
@Service
public class InvoiceGenerationService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceGenerationService.class);

    private final InvoiceRepository invoiceRepository;
    private final PaymentPort paymentPort;

    public InvoiceGenerationService(InvoiceRepository invoiceRepository,
                                     PaymentPort paymentPort) {
        this.invoiceRepository = invoiceRepository;
        this.paymentPort = paymentPort;
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
            log.info("Invoice paid: id={}, paymentId={}", invoice.getId(), result.paymentId());
            return true;
        } else {
            log.warn("Invoice payment failed: id={}, reason={}", invoice.getId(), result.failureReason());
            return false;
        }
    }

    // -- Query methods --

    public Optional<Invoice> findById(String id) {
        return invoiceRepository.findById(id);
    }

    public List<Invoice> listByTenant(String tenantId, int limit, int offset) {
        return invoiceRepository.findByTenant(tenantId, limit, offset);
    }

    public List<InvoiceLineItem> getLineItems(String invoiceId) {
        return invoiceRepository.findLineItemsByInvoice(invoiceId);
    }
}
