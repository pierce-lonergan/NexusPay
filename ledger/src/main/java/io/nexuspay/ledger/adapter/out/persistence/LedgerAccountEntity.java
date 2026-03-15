package io.nexuspay.ledger.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "ledger_accounts")
public class LedgerAccountEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "posted_balance", nullable = false)
    private long postedBalance;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LedgerAccountEntity() {}

    public LedgerAccountEntity(String id, String name, String type, String currency,
                                long postedBalance, long version, String tenantId,
                                Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.currency = currency;
        this.postedBalance = postedBalance;
        this.version = version;
        this.tenantId = tenantId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getCurrency() { return currency; }
    public long getPostedBalance() { return postedBalance; }
    public long getVersion() { return version; }
    public String getTenantId() { return tenantId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setPostedBalance(long postedBalance) { this.postedBalance = postedBalance; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
