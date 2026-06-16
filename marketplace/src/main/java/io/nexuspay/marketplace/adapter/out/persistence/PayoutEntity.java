package io.nexuspay.marketplace.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code payouts} table.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@Entity
@Table(name = "payouts")
public class PayoutEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "connected_account_id", nullable = false, length = 64)
    private String connectedAccountId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "method", nullable = false, length = 16)
    private String method;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "failure_reason", length = 256)
    private String failureReason;

    @Column(name = "external_reference", length = 128)
    private String externalReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // SEC-25 (V4032): PROCESSING-recovery bookkeeping. processing_since is stamped by the SEC-11 claim
    // UPDATE so the reconciler can tell HOW LONG a row has been PROCESSING (created_at is creation time,
    // not claim time). The rest mirror B-022's pending_approvals reconcile columns.
    @Column(name = "processing_since")
    private Instant processingSince;

    @Column(name = "reconcile_attempts", nullable = false)
    private int reconcileAttempts;

    @Column(name = "next_reconcile_at")
    private Instant nextReconcileAt;

    @Column(name = "last_reconcile_error")
    private String lastReconcileError;

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

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

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

    public Instant getProcessingSince() { return processingSince; }
    public void setProcessingSince(Instant processingSince) { this.processingSince = processingSince; }

    public int getReconcileAttempts() { return reconcileAttempts; }
    public void setReconcileAttempts(int reconcileAttempts) { this.reconcileAttempts = reconcileAttempts; }

    public Instant getNextReconcileAt() { return nextReconcileAt; }
    public void setNextReconcileAt(Instant nextReconcileAt) { this.nextReconcileAt = nextReconcileAt; }

    public String getLastReconcileError() { return lastReconcileError; }
    public void setLastReconcileError(String lastReconcileError) { this.lastReconcileError = lastReconcileError; }
}
