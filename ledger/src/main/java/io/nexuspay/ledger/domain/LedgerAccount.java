package io.nexuspay.ledger.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A ledger account in the chart of accounts.
 * Tracks a running posted_balance with optimistic concurrency (version).
 */
public class LedgerAccount {

    private final String id;
    private final String name;
    private final AccountType type;
    private final String currency;
    private long postedBalance;
    private long version;
    private final String tenantId;
    private final Instant createdAt;
    private Instant updatedAt;

    public LedgerAccount(String id, String name, AccountType type, String currency,
                         long postedBalance, long version, String tenantId,
                         Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.type = Objects.requireNonNull(type);
        this.currency = Objects.requireNonNull(currency);
        this.postedBalance = postedBalance;
        this.version = version;
        this.tenantId = Objects.requireNonNull(tenantId);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Applies a posting amount to this account's balance.
     * Positive = debit, negative = credit.
     */
    public void applyPosting(long amount) {
        this.postedBalance += amount;
        this.version++;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public AccountType getType() { return type; }
    public String getCurrency() { return currency; }
    public long getPostedBalance() { return postedBalance; }
    public long getVersion() { return version; }
    public String getTenantId() { return tenantId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
