package io.nexuspay.b2b.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Domain model representing a B2B invoice between buyer and seller.
 * Created from an approved purchase order and tracks payment against terms.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public class B2bInvoice {

    private String id;
    private String tenantId;
    private String purchaseOrderId;
    private String buyerId;
    private String sellerId;
    private String invoiceNumber;
    private long amount;
    private long taxAmount;
    private String currency;
    private InvoiceStatus status;
    private PaymentTerms terms;
    private LocalDate dueDate;
    private Instant paidAt;
    private Instant createdAt;

    public static B2bInvoice create(String tenantId, String purchaseOrderId, String buyerId,
                                     String sellerId, String invoiceNumber, long amount,
                                     long taxAmount, String currency, PaymentTerms terms,
                                     LocalDate dueDate) {
        B2bInvoice invoice = new B2bInvoice();
        invoice.id = "inv_" + UUID.randomUUID().toString().replace("-", "");
        invoice.tenantId = tenantId;
        invoice.purchaseOrderId = purchaseOrderId;
        invoice.buyerId = buyerId;
        invoice.sellerId = sellerId;
        invoice.invoiceNumber = invoiceNumber;
        invoice.amount = amount;
        invoice.taxAmount = taxAmount;
        invoice.currency = currency;
        invoice.status = InvoiceStatus.DRAFT;
        invoice.terms = terms;
        invoice.dueDate = dueDate;
        invoice.createdAt = Instant.now();
        return invoice;
    }

    public void send() {
        this.status = InvoiceStatus.SENT;
    }

    public void markPaid() {
        this.status = InvoiceStatus.PAID;
        this.paidAt = Instant.now();
    }

    public void markOverdue() {
        this.status = InvoiceStatus.OVERDUE;
    }

    public void cancel() {
        this.status = InvoiceStatus.CANCELLED;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getPurchaseOrderId() { return purchaseOrderId; }
    public void setPurchaseOrderId(String purchaseOrderId) { this.purchaseOrderId = purchaseOrderId; }

    public String getBuyerId() { return buyerId; }
    public void setBuyerId(String buyerId) { this.buyerId = buyerId; }

    public String getSellerId() { return sellerId; }
    public void setSellerId(String sellerId) { this.sellerId = sellerId; }

    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public long getTaxAmount() { return taxAmount; }
    public void setTaxAmount(long taxAmount) { this.taxAmount = taxAmount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public InvoiceStatus getStatus() { return status; }
    public void setStatus(InvoiceStatus status) { this.status = status; }

    public PaymentTerms getTerms() { return terms; }
    public void setTerms(PaymentTerms terms) { this.terms = terms; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
