package io.nexuspay.b2b.application.service;

import io.nexuspay.b2b.application.port.in.ManageB2bInvoiceUseCase;
import io.nexuspay.b2b.application.port.out.B2bEventPublisher;
import io.nexuspay.b2b.application.port.out.B2bRepository;
import io.nexuspay.b2b.domain.B2bInvoice;
import io.nexuspay.b2b.domain.PurchaseOrder;
import io.nexuspay.b2b.domain.PurchaseOrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Service for B2B invoice lifecycle management.
 * Creates invoices from approved purchase orders and tracks payment.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@Service
public class B2bInvoiceService implements ManageB2bInvoiceUseCase {

    private static final Logger log = LoggerFactory.getLogger(B2bInvoiceService.class);

    private final B2bRepository repository;
    private final B2bEventPublisher eventPublisher;

    public B2bInvoiceService(B2bRepository repository, B2bEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public InvoiceResult createInvoiceFromPO(String purchaseOrderId, String tenantId, String invoiceNumber) {
        PurchaseOrder po = repository.findPurchaseOrderById(purchaseOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase order not found: " + purchaseOrderId));

        if (po.getStatus() != PurchaseOrderStatus.APPROVED && po.getStatus() != PurchaseOrderStatus.SUBMITTED) {
            throw new IllegalStateException("Can only invoice APPROVED or SUBMITTED purchase orders, current: " + po.getStatus());
        }

        B2bInvoice invoice = B2bInvoice.create(
                tenantId, purchaseOrderId, po.getBuyerId(), po.getSellerId(),
                invoiceNumber, po.getAmount(), po.getTaxAmount(),
                po.getCurrency(), po.getTerms(), po.getDueDate());

        invoice = repository.saveInvoice(invoice);

        // Mark PO as invoiced
        po.markInvoiced();
        repository.savePurchaseOrder(po);

        eventPublisher.publishEvent("B2bInvoice", invoice.getId(), "InvoiceCreatedFromPO",
                Map.of("invoiceNumber", invoiceNumber, "purchaseOrderId", purchaseOrderId,
                        "amount", po.getAmount(), "tenantId", tenantId),
                tenantId);

        log.info("Invoice created from PO: invoiceId={}, poId={}, amount={}", invoice.getId(), purchaseOrderId, po.getAmount());
        return toResult(invoice);
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceResult getInvoice(String invoiceId, String tenantId) {
        return toResult(findOrThrow(invoiceId));
    }

    @Override
    @Transactional
    public InvoiceResult sendInvoice(String invoiceId, String tenantId) {
        B2bInvoice invoice = findOrThrow(invoiceId);
        invoice.send();
        invoice = repository.saveInvoice(invoice);

        eventPublisher.publishEvent("B2bInvoice", invoiceId, "InvoiceSent",
                Map.of("invoiceNumber", invoice.getInvoiceNumber(), "tenantId", tenantId), tenantId);

        log.info("Invoice sent: id={}", invoiceId);
        return toResult(invoice);
    }

    @Override
    @Transactional
    public InvoiceResult markInvoicePaid(String invoiceId, String tenantId) {
        B2bInvoice invoice = findOrThrow(invoiceId);
        invoice.markPaid();
        invoice = repository.saveInvoice(invoice);

        // Also mark the associated PO as paid
        if (invoice.getPurchaseOrderId() != null) {
            repository.findPurchaseOrderById(invoice.getPurchaseOrderId()).ifPresent(po -> {
                po.markPaid();
                repository.savePurchaseOrder(po);
            });
        }

        eventPublisher.publishEvent("B2bInvoice", invoiceId, "InvoicePaid",
                Map.of("invoiceNumber", invoice.getInvoiceNumber(),
                        "amount", invoice.getAmount(), "tenantId", tenantId),
                tenantId);

        log.info("Invoice paid: id={}, amount={}", invoiceId, invoice.getAmount());
        return toResult(invoice);
    }

    private B2bInvoice findOrThrow(String invoiceId) {
        return repository.findInvoiceById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + invoiceId));
    }

    private InvoiceResult toResult(B2bInvoice inv) {
        return new InvoiceResult(
                inv.getId(), inv.getPurchaseOrderId(), inv.getInvoiceNumber(),
                inv.getBuyerId(), inv.getSellerId(), inv.getAmount(), inv.getTaxAmount(),
                inv.getCurrency(), inv.getStatus(), inv.getTerms(), inv.getDueDate(),
                inv.getPaidAt(), inv.getCreatedAt());
    }
}
