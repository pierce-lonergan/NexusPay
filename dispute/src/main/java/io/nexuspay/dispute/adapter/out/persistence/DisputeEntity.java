package io.nexuspay.dispute.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity mapped to the {@code disputes} table.
 *
 * @since 0.2.4 (Sprint 2.4)
 */
@Entity
@Table(
        name = "disputes",
        // SEC-BATCH-2 (SEC-01): mirrors the authoritative DB UNIQUE added in
        // Flyway V4026 (uq_disputes_tenant_external). The DB constraint is the
        // source of truth; this is Hibernate-side documentation only (ddl-auto
        // is `validate`, so Hibernate does not create it).
        uniqueConstraints = @UniqueConstraint(
                name = "uq_disputes_tenant_external",
                columnNames = {"tenant_id", "external_dispute_id"})
)
public class DisputeEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "payment_id", nullable = false, length = 64)
    private String paymentId;

    @Column(name = "external_dispute_id", length = 128)
    private String externalDisputeId;

    @Column(name = "reason_code", nullable = false, length = 32)
    private String reasonCode;

    @Column(name = "reason_description", columnDefinition = "TEXT")
    private String reasonDescription;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(length = 16)
    private String network;

    @Column(name = "evidence_due_date")
    private Instant evidenceDueDate;

    @Column(name = "evidence_submitted_at")
    private Instant evidenceSubmittedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(length = 16)
    private String outcome;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // -- Getters & setters --

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public String getExternalDisputeId() { return externalDisputeId; }
    public void setExternalDisputeId(String externalDisputeId) { this.externalDisputeId = externalDisputeId; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getReasonDescription() { return reasonDescription; }
    public void setReasonDescription(String reasonDescription) { this.reasonDescription = reasonDescription; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }
    public Instant getEvidenceDueDate() { return evidenceDueDate; }
    public void setEvidenceDueDate(Instant evidenceDueDate) { this.evidenceDueDate = evidenceDueDate; }
    public Instant getEvidenceSubmittedAt() { return evidenceSubmittedAt; }
    public void setEvidenceSubmittedAt(Instant evidenceSubmittedAt) { this.evidenceSubmittedAt = evidenceSubmittedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
