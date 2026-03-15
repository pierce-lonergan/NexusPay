package io.nexuspay.reconciliation.domain;

import io.nexuspay.common.id.PrefixedId;

import java.time.Instant;

/**
 * Aggregate root representing a single reconciliation batch execution.
 *
 * <p>A reconciliation run ingests a settlement file from a PSP, parses it
 * into individual settlement records, and matches each record against
 * NexusPay's payment records and ledger entries.</p>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
public class ReconciliationRun {

    private String id;
    private String tenantId;
    private String provider;
    private String fileName;
    private RunStatus status;
    private int totalRecords;
    private int matchedCount;
    private int unmatchedCount;
    private int exceptionCount;
    private Instant startedAt;
    private Instant completedAt;
    private Instant createdAt;

    public enum RunStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    public ReconciliationRun() {
    }

    /**
     * Creates a new reconciliation run in PENDING status.
     */
    public static ReconciliationRun create(String tenantId, String provider, String fileName) {
        ReconciliationRun run = new ReconciliationRun();
        run.id = PrefixedId.reconciliationRun();
        run.tenantId = tenantId;
        run.provider = provider;
        run.fileName = fileName;
        run.status = RunStatus.PENDING;
        run.totalRecords = 0;
        run.matchedCount = 0;
        run.unmatchedCount = 0;
        run.exceptionCount = 0;
        run.createdAt = Instant.now();
        return run;
    }

    public void start() {
        if (status != RunStatus.PENDING) {
            throw new IllegalStateException("Cannot start run in status: " + status);
        }
        this.status = RunStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public void complete(int total, int matched, int unmatched, int exceptions) {
        this.status = RunStatus.COMPLETED;
        this.totalRecords = total;
        this.matchedCount = matched;
        this.unmatchedCount = unmatched;
        this.exceptionCount = exceptions;
        this.completedAt = Instant.now();
    }

    public void fail() {
        this.status = RunStatus.FAILED;
        this.completedAt = Instant.now();
    }

    public boolean isCompleted() {
        return status == RunStatus.COMPLETED;
    }

    public double matchRate() {
        if (totalRecords == 0) return 0.0;
        return (double) matchedCount / totalRecords * 100.0;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }
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
