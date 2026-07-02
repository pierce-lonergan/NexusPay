package io.nexuspay.b2b.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Domain model representing a B2B purchase order.
 * Tracks the PO lifecycle from draft through invoicing and payment.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public class PurchaseOrder {

    private String id;
    private String tenantId;
    private String buyerId;
    private String sellerId;
    private String poNumber;
    private long amount;
    private long taxAmount;
    private String currency;
    private PurchaseOrderStatus status;
    private PaymentTerms terms;
    private List<LineItem> lineItems;
    private LocalDate dueDate;
    private Instant createdAt;
    private Instant updatedAt;
    /**
     * GAP-068: the authenticated principal that CREATED this PO, stamped at create. Used by the
     * maker-checker review path to enforce creator != approver fail-closed (when recorded; nullable
     * for legacy rows — requester != reviewer is always enforced regardless).
     */
    private String createdBy;

    public static PurchaseOrder create(String tenantId, String buyerId, String sellerId,
                                        String poNumber, String currency, PaymentTerms terms) {
        PurchaseOrder po = new PurchaseOrder();
        po.id = "po_" + UUID.randomUUID().toString().replace("-", "");
        po.tenantId = tenantId;
        po.buyerId = buyerId;
        po.sellerId = sellerId;
        po.poNumber = poNumber;
        po.amount = 0;
        po.taxAmount = 0;
        po.currency = currency;
        po.status = PurchaseOrderStatus.DRAFT;
        po.terms = terms;
        po.lineItems = new ArrayList<>();
        po.createdAt = Instant.now();
        po.updatedAt = Instant.now();
        return po;
    }

    public void addLineItem(LineItem item) {
        this.lineItems.add(item);
        recalculateAmount();
    }

    public void submit() {
        if (this.status != PurchaseOrderStatus.DRAFT) {
            throw new IllegalStateException("Can only submit DRAFT purchase orders");
        }
        this.status = PurchaseOrderStatus.SUBMITTED;
        this.updatedAt = Instant.now();
    }

    public void approve() {
        if (this.status != PurchaseOrderStatus.SUBMITTED) {
            throw new IllegalStateException("Can only approve SUBMITTED purchase orders");
        }
        this.status = PurchaseOrderStatus.APPROVED;
        calculateDueDate();
        this.updatedAt = Instant.now();
    }

    public void markInvoiced() {
        this.status = PurchaseOrderStatus.INVOICED;
        this.updatedAt = Instant.now();
    }

    public void markPaid() {
        this.status = PurchaseOrderStatus.PAID;
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        this.status = PurchaseOrderStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    private void recalculateAmount() {
        this.amount = lineItems.stream().mapToLong(LineItem::totalCost).sum();
    }

    private void calculateDueDate() {
        if (terms == null) return;
        LocalDate today = LocalDate.now();
        this.dueDate = switch (terms) {
            case DUE_ON_RECEIPT -> today;
            case NET_30 -> today.plusDays(30);
            case NET_60 -> today.plusDays(60);
            case NET_90 -> today.plusDays(90);
        };
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getBuyerId() { return buyerId; }
    public void setBuyerId(String buyerId) { this.buyerId = buyerId; }

    public String getSellerId() { return sellerId; }
    public void setSellerId(String sellerId) { this.sellerId = sellerId; }

    public String getPoNumber() { return poNumber; }
    public void setPoNumber(String poNumber) { this.poNumber = poNumber; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public long getTaxAmount() { return taxAmount; }
    public void setTaxAmount(long taxAmount) { this.taxAmount = taxAmount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public PurchaseOrderStatus getStatus() { return status; }
    public void setStatus(PurchaseOrderStatus status) { this.status = status; }

    public PaymentTerms getTerms() { return terms; }
    public void setTerms(PaymentTerms terms) { this.terms = terms; }

    public List<LineItem> getLineItems() { return lineItems; }
    public void setLineItems(List<LineItem> lineItems) { this.lineItems = lineItems; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
