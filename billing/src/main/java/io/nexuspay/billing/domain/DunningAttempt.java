package io.nexuspay.billing.domain;

import io.nexuspay.common.id.PrefixedId;

import java.time.Instant;

/**
 * Record of a payment retry attempt during the dunning process.
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
public class DunningAttempt {

    public enum Status { PENDING, SUCCESS, FAILED }

    private String id;
    private String subscriptionId;
    private String invoiceId;
    private String tenantId;
    private int attemptNumber;
    private String paymentId;
    private Status status;
    private Instant scheduledAt;
    private Instant attemptedAt;
    private String failureReason;
    private Instant createdAt;

    public DunningAttempt() {
    }

    public static DunningAttempt schedule(String subscriptionId, String invoiceId,
                                           String tenantId, int attemptNumber,
                                           Instant scheduledAt) {
        DunningAttempt da = new DunningAttempt();
        da.id = PrefixedId.dunningAttempt();
        da.subscriptionId = subscriptionId;
        da.invoiceId = invoiceId;
        da.tenantId = tenantId;
        da.attemptNumber = attemptNumber;
        da.status = Status.PENDING;
        da.scheduledAt = scheduledAt;
        da.createdAt = Instant.now();
        return da;
    }

    public void markSuccess(String paymentId) {
        this.status = Status.SUCCESS;
        this.paymentId = paymentId;
        this.attemptedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = Status.FAILED;
        this.failureReason = reason;
        this.attemptedAt = Instant.now();
    }

    // -- Getters & setters --

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public int getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(int attemptNumber) { this.attemptNumber = attemptNumber; }
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }
    public Instant getAttemptedAt() { return attemptedAt; }
    public void setAttemptedAt(Instant attemptedAt) { this.attemptedAt = attemptedAt; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
