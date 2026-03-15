package io.nexuspay.billing.domain;

import java.time.Instant;

/**
 * Individual charge or credit on an {@link Invoice}.
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
public class InvoiceLineItem {

    private String id;
    private String invoiceId;
    private String tenantId;
    private String description;
    private long amount;
    private String currency;
    private int quantity;
    private boolean proration;
    private Instant periodStart;
    private Instant periodEnd;

    public InvoiceLineItem() {
    }

    public InvoiceLineItem(String id, String invoiceId, String tenantId,
                           String description, long amount, String currency,
                           int quantity, boolean proration,
                           Instant periodStart, Instant periodEnd) {
        this.id = id;
        this.invoiceId = invoiceId;
        this.tenantId = tenantId;
        this.description = description;
        this.amount = amount;
        this.currency = currency;
        this.quantity = quantity;
        this.proration = proration;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
    }

    // -- Getters & setters --

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public boolean isProration() { return proration; }
    public void setProration(boolean proration) { this.proration = proration; }
    public Instant getPeriodStart() { return periodStart; }
    public void setPeriodStart(Instant periodStart) { this.periodStart = periodStart; }
    public Instant getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(Instant periodEnd) { this.periodEnd = periodEnd; }
}
