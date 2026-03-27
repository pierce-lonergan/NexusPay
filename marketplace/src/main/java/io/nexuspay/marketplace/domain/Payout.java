package io.nexuspay.marketplace.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain model representing a payout disbursement to a connected account.
 * Tracks the payout lifecycle from scheduling through execution.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public class Payout {

    private String id;
    private String connectedAccountId;
    private String tenantId;
    private long amount;
    private String currency;
    private PayoutStatus status;
    private PayoutMethod method;
    private Instant scheduledAt;
    private Instant paidAt;
    private String failureReason;
    private String externalReference;
    private Instant createdAt;

    public static Payout create(String connectedAccountId, String tenantId, long amount,
                                 String currency, PayoutMethod method) {
        Payout payout = new Payout();
        payout.id = "po_" + UUID.randomUUID().toString().replace("-", "");
        payout.connectedAccountId = connectedAccountId;
        payout.tenantId = tenantId;
        payout.amount = amount;
        payout.currency = currency;
        payout.status = PayoutStatus.PENDING;
        payout.method = method;
        payout.createdAt = Instant.now();
        return payout;
    }

    public void markProcessing() {
        this.status = PayoutStatus.PROCESSING;
    }

    public void markPaid(String externalReference) {
        this.status = PayoutStatus.PAID;
        this.paidAt = Instant.now();
        this.externalReference = externalReference;
    }

    public void markFailed(String reason) {
        this.status = PayoutStatus.FAILED;
        this.failureReason = reason;
    }

    public void schedule(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getConnectedAccountId() { return connectedAccountId; }
    public void setConnectedAccountId(String connectedAccountId) { this.connectedAccountId = connectedAccountId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public PayoutStatus getStatus() { return status; }
    public void setStatus(PayoutStatus status) { this.status = status; }

    public PayoutMethod getMethod() { return method; }
    public void setMethod(PayoutMethod method) { this.method = method; }

    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }

    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public String getExternalReference() { return externalReference; }
    public void setExternalReference(String externalReference) { this.externalReference = externalReference; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
