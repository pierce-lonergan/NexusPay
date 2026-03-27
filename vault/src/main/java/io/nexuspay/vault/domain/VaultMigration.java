package io.nexuspay.vault.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks a vault-to-vault migration from an external provider
 * (Spreedly, Stripe, Braintree) into the NexusPay vault.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public class VaultMigration {

    private String id;
    private String tenantId;
    private String sourceProvider;
    private MigrationStatus status;
    private int totalCards;
    private int migratedCount;
    private int failedCount;
    private Instant startedAt;
    private Instant completedAt;
    private Instant createdAt;

    public static VaultMigration create(String tenantId, String sourceProvider, int totalCards) {
        VaultMigration m = new VaultMigration();
        m.id = "vm_" + UUID.randomUUID().toString().replace("-", "");
        m.tenantId = tenantId;
        m.sourceProvider = sourceProvider;
        m.status = MigrationStatus.PENDING;
        m.totalCards = totalCards;
        m.migratedCount = 0;
        m.failedCount = 0;
        m.createdAt = Instant.now();
        return m;
    }

    public void start() {
        this.status = MigrationStatus.IN_PROGRESS;
        this.startedAt = Instant.now();
    }

    public void complete() {
        this.status = MigrationStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void fail() {
        this.status = MigrationStatus.FAILED;
        this.completedAt = Instant.now();
    }

    public void incrementMigrated() { this.migratedCount++; }
    public void incrementFailed() { this.failedCount++; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getSourceProvider() { return sourceProvider; }
    public void setSourceProvider(String sourceProvider) { this.sourceProvider = sourceProvider; }

    public MigrationStatus getStatus() { return status; }
    public void setStatus(MigrationStatus status) { this.status = status; }

    public int getTotalCards() { return totalCards; }
    public void setTotalCards(int totalCards) { this.totalCards = totalCards; }

    public int getMigratedCount() { return migratedCount; }
    public void setMigratedCount(int migratedCount) { this.migratedCount = migratedCount; }

    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
