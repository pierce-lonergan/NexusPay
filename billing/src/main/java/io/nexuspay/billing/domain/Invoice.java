package io.nexuspay.billing.domain;

import io.nexuspay.common.id.PrefixedId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A billing invoice generated for a subscription period.
 *
 * <p>Invoices follow the lifecycle: DRAFT → OPEN → PAID / VOID / UNCOLLECTIBLE.</p>
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
public class Invoice {

    private String id;
    private String tenantId;
    private String subscriptionId;
    private String customerId;
    private InvoiceStatus status;
    private String currency;
    private long subtotal;
    private long tax;
    private long total;
    private long amountPaid;
    private long amountDue;
    private String paymentId;
    private Instant dueDate;
    private Instant paidAt;
    private Instant periodStart;
    private Instant periodEnd;
    private Instant createdAt;

    private final List<InvoiceLineItem> lineItems = new ArrayList<>();

    public Invoice() {
    }

    /**
     * Creates a draft invoice for a subscription period.
     */
    public static Invoice createForSubscription(String tenantId, String subscriptionId,
                                                 String customerId, String currency,
                                                 Instant periodStart, Instant periodEnd) {
        Invoice inv = new Invoice();
        inv.id = PrefixedId.invoice();
        inv.tenantId = tenantId;
        inv.subscriptionId = subscriptionId;
        inv.customerId = customerId;
        inv.status = InvoiceStatus.DRAFT;
        inv.currency = currency;
        inv.periodStart = periodStart;
        inv.periodEnd = periodEnd;
        inv.dueDate = periodEnd;
        inv.createdAt = Instant.now();
        return inv;
    }

    /**
     * Adds a line item and recalculates totals.
     */
    public void addLineItem(InvoiceLineItem item) {
        this.lineItems.add(item);
        recalculate();
    }

    /**
     * Finalises the invoice and opens it for payment.
     */
    public void finalise() {
        if (status != InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Can only finalise DRAFT invoices, was " + status);
        }
        recalculate();
        this.status = InvoiceStatus.OPEN;
        this.amountDue = this.total;
    }

    /**
     * Marks the invoice as paid.
     */
    public void markPaid(String paymentId) {
        if (status != InvoiceStatus.OPEN) {
            throw new IllegalStateException("Can only pay OPEN invoices, was " + status);
        }
        this.status = InvoiceStatus.PAID;
        this.paymentId = paymentId;
        this.amountPaid = this.total;
        this.amountDue = 0;
        this.paidAt = Instant.now();
    }

    /**
     * Voids the invoice (e.g., billing error).
     */
    public void voidInvoice() {
        if (status == InvoiceStatus.PAID) {
            throw new IllegalStateException("Cannot void a paid invoice");
        }
        this.status = InvoiceStatus.VOID;
        this.amountDue = 0;
    }

    /**
     * Marks as uncollectible after dunning exhaustion.
     */
    public void markUncollectible() {
        this.status = InvoiceStatus.UNCOLLECTIBLE;
    }

    private void recalculate() {
        this.subtotal = lineItems.stream().mapToLong(InvoiceLineItem::getAmount).sum();
        // Tax calculation placeholder — Phase 3 tax engine
        this.tax = 0;
        this.total = this.subtotal + this.tax;
    }

    // -- Getters & setters --

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String id) { this.subscriptionId = id; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public InvoiceStatus getStatus() { return status; }
    public void setStatus(InvoiceStatus status) { this.status = status; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public long getSubtotal() { return subtotal; }
    public void setSubtotal(long subtotal) { this.subtotal = subtotal; }
    public long getTax() { return tax; }
    public void setTax(long tax) { this.tax = tax; }
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public long getAmountPaid() { return amountPaid; }
    public void setAmountPaid(long amountPaid) { this.amountPaid = amountPaid; }
    public long getAmountDue() { return amountDue; }
    public void setAmountDue(long amountDue) { this.amountDue = amountDue; }
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public Instant getDueDate() { return dueDate; }
    public void setDueDate(Instant dueDate) { this.dueDate = dueDate; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
    public Instant getPeriodStart() { return periodStart; }
    public void setPeriodStart(Instant periodStart) { this.periodStart = periodStart; }
    public Instant getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(Instant periodEnd) { this.periodEnd = periodEnd; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<InvoiceLineItem> getLineItems() { return Collections.unmodifiableList(lineItems); }
}
