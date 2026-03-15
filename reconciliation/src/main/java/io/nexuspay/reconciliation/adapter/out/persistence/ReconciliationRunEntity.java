package io.nexuspay.reconciliation.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity for reconciliation_runs table.
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRunEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(name = "file_name", length = 256)
    private String fileName;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "total_records")
    private int totalRecords;

    @Column(name = "matched_count")
    private int matchedCount;

    @Column(name = "unmatched_count")
    private int unmatchedCount;

    @Column(name = "exception_count")
    private int exceptionCount;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReconciliationRunEntity() {}

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getTotalRecords() { return totalRecords; }
    public void setTotalRecords(int totalRecords) { this.totalRecords = totalRecords; }
    public int getMatchedCount() { return matchedCount; }
    public void setMatchedCount(int matchedCount) { this.matchedCount = matchedCount; }
    public int getUnmatchedCount() { return unmatchedCount; }
    public void setUnmatchedCount(int unmatchedCount) { this.unmatchedCount = unmatchedCount; }
    public int getExceptionCount() { return exceptionCount; }
    public void setExceptionCount(int exceptionCount) { this.exceptionCount = exceptionCount; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
