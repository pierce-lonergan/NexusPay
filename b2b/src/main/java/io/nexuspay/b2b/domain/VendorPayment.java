package io.nexuspay.b2b.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain model representing a payment disbursement to a vendor.
 * Supports batching, approval workflows, and multiple payment methods.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public class VendorPayment {

    private String id;
    private String tenantId;
    private String vendorId;
    private long amount;
    private String currency;
    private VendorPaymentMethod method;
    private VendorPaymentStatus status;
    private String batchId;
    private String remittanceInfo;
    private Instant scheduledAt;
    private Instant paidAt;
    private String externalReference;
    private Instant createdAt;
    /**
     * GAP-068: the authenticated principal that CREATED this payment, stamped at create. Used by the
     * maker-checker review path to enforce creator != approver fail-closed (when recorded; nullable
     * for legacy rows — requester != reviewer is always enforced regardless).
     */
    private String createdBy;

    public static VendorPayment create(String tenantId, String vendorId, long amount,
                                        String currency, VendorPaymentMethod method) {
        VendorPayment vp = new VendorPayment();
        vp.id = "vp_" + UUID.randomUUID().toString().replace("-", "");
        vp.tenantId = tenantId;
        vp.vendorId = vendorId;
        vp.amount = amount;
        vp.currency = currency;
        vp.method = method;
        vp.status = VendorPaymentStatus.PENDING;
        vp.createdAt = Instant.now();
        return vp;
    }

    public void approve() {
        if (this.status != VendorPaymentStatus.PENDING) {
            throw new IllegalStateException("Can only approve PENDING vendor payments");
        }
        this.status = VendorPaymentStatus.APPROVED;
    }

    public void markProcessing() {
        this.status = VendorPaymentStatus.PROCESSING;
    }

    public void markPaid(String externalReference) {
        this.status = VendorPaymentStatus.PAID;
        this.paidAt = Instant.now();
        this.externalReference = externalReference;
    }

    public void markFailed(String reason) {
        this.status = VendorPaymentStatus.FAILED;
        this.remittanceInfo = reason;
    }

    public void assignToBatch(String batchId) {
        this.batchId = batchId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getVendorId() { return vendorId; }
    public void setVendorId(String vendorId) { this.vendorId = vendorId; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public VendorPaymentMethod getMethod() { return method; }
    public void setMethod(VendorPaymentMethod method) { this.method = method; }

    public VendorPaymentStatus getStatus() { return status; }
    public void setStatus(VendorPaymentStatus status) { this.status = status; }

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public String getRemittanceInfo() { return remittanceInfo; }
    public void setRemittanceInfo(String remittanceInfo) { this.remittanceInfo = remittanceInfo; }

    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }

    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }

    public String getExternalReference() { return externalReference; }
    public void setExternalReference(String externalReference) { this.externalReference = externalReference; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
