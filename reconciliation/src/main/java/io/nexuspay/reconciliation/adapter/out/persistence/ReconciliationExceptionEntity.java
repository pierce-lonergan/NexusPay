package io.nexuspay.reconciliation.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity for reconciliation_exceptions table.
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Entity
@Table(name = "reconciliation_exceptions",
        indexes = {
            @Index(name = "idx_recon_exceptions_status", columnList = "status"),
            @Index(name = "idx_recon_exceptions_run", columnList = "reconciliation_run_id")
        })
public class ReconciliationExceptionEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "reconciliation_run_id", nullable = false, length = 64)
    private String reconciliationRunId;

    @Column(name = "settlement_record_id", length = 64)
    private String settlementRecordId;

    @Column(name = "exception_type", nullable = false, length = 32)
    private String exceptionType;

    @Column(name = "expected_amount")
    private Long expectedAmount;

    @Column(name = "actual_amount")
    private Long actualAmount;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "assigned_to", length = 128)
    private String assignedTo;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_notes", columnDefinition = "text")
    private String resolutionNotes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReconciliationExceptionEntity() {}

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getReconciliationRunId() { return reconciliationRunId; }
    public void setReconciliationRunId(String reconciliationRunId) { this.reconciliationRunId = reconciliationRunId; }
    public String getSettlementRecordId() { return settlementRecordId; }
    public void setSettlementRecordId(String settlementRecordId) { this.settlementRecordId = settlementRecordId; }
    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }
    public Long getExpectedAmount() { return expectedAmount; }
    public void setExpectedAmount(Long expectedAmount) { this.expectedAmount = expectedAmount; }
    public Long getActualAmount() { return actualAmount; }
    public void setActualAmount(Long actualAmount) { this.actualAmount = actualAmount; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAssignedTo() { return assignedTo; }
    public void setAssignedTo(String assignedTo) { this.assignedTo = assignedTo; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getResolutionNotes() { return resolutionNotes; }
    public void setResolutionNotes(String resolutionNotes) { this.resolutionNotes = resolutionNotes; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
