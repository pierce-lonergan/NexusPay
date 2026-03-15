package io.nexuspay.reconciliation.domain;

import io.nexuspay.common.id.PrefixedId;

import java.time.Instant;

/**
 * An unresolved discrepancy found during reconciliation.
 *
 * <p>Exceptions are created when the three-way matching engine detects
 * mismatches between settlement records, payments, and ledger entries.
 * They follow a lifecycle: OPEN → INVESTIGATING → RESOLVED/WRITTEN_OFF.</p>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
public class ReconciliationException {

    private String id;
    private String tenantId;
    private String reconciliationRunId;
    private String settlementRecordId;
    private MatchResult.ExceptionType exceptionType;
    private Long expectedAmount;
    private Long actualAmount;
    private String description;
    private ExceptionStatus status;
    private String assignedTo;
    private Instant resolvedAt;
    private String resolutionNotes;
    private Instant createdAt;

    public enum ExceptionStatus {
        OPEN, INVESTIGATING, RESOLVED, WRITTEN_OFF
    }

    public ReconciliationException() {
    }

    public static ReconciliationException create(String tenantId, String runId,
                                                  String settlementRecordId,
                                                  MatchResult.ExceptionType type,
                                                  Long expectedAmount, Long actualAmount,
                                                  String description) {
        ReconciliationException ex = new ReconciliationException();
        ex.id = PrefixedId.reconciliationException();
        ex.tenantId = tenantId;
        ex.reconciliationRunId = runId;
        ex.settlementRecordId = settlementRecordId;
        ex.exceptionType = type;
        ex.expectedAmount = expectedAmount;
        ex.actualAmount = actualAmount;
        ex.description = description;
        ex.status = ExceptionStatus.OPEN;
        ex.createdAt = Instant.now();
        return ex;
    }

    public void assignTo(String userId) {
        this.assignedTo = userId;
        this.status = ExceptionStatus.INVESTIGATING;
    }

    public void resolve(String notes) {
        this.status = ExceptionStatus.RESOLVED;
        this.resolutionNotes = notes;
        this.resolvedAt = Instant.now();
    }

    public void writeOff(String notes) {
        this.status = ExceptionStatus.WRITTEN_OFF;
        this.resolutionNotes = notes;
        this.resolvedAt = Instant.now();
    }

    public long discrepancy() {
        if (expectedAmount == null || actualAmount == null) return 0;
        return Math.abs(expectedAmount - actualAmount);
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getReconciliationRunId() { return reconciliationRunId; }
    public void setReconciliationRunId(String reconciliationRunId) { this.reconciliationRunId = reconciliationRunId; }
    public String getSettlementRecordId() { return settlementRecordId; }
    public void setSettlementRecordId(String settlementRecordId) { this.settlementRecordId = settlementRecordId; }
    public MatchResult.ExceptionType getExceptionType() { return exceptionType; }
    public void setExceptionType(MatchResult.ExceptionType exceptionType) { this.exceptionType = exceptionType; }
    public Long getExpectedAmount() { return expectedAmount; }
    public void setExpectedAmount(Long expectedAmount) { this.expectedAmount = expectedAmount; }
    public Long getActualAmount() { return actualAmount; }
    public void setActualAmount(Long actualAmount) { this.actualAmount = actualAmount; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public ExceptionStatus getStatus() { return status; }
    public void setStatus(ExceptionStatus status) { this.status = status; }
    public String getAssignedTo() { return assignedTo; }
    public void setAssignedTo(String assignedTo) { this.assignedTo = assignedTo; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getResolutionNotes() { return resolutionNotes; }
    public void setResolutionNotes(String resolutionNotes) { this.resolutionNotes = resolutionNotes; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
