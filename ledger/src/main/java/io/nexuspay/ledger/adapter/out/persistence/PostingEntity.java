package io.nexuspay.ledger.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "postings")
public class PostingEntity {

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    private JournalEntryEntity journalEntry;

    @Column(name = "ledger_account_id", nullable = false, length = 64)
    private String ledgerAccountId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    protected PostingEntity() {}

    public PostingEntity(String id, String ledgerAccountId, long amount, String currency) {
        this.id = id;
        this.ledgerAccountId = ledgerAccountId;
        this.amount = amount;
        this.currency = currency;
        this.tenantId = "default";
    }

    public PostingEntity(String id, String ledgerAccountId, long amount, String currency, String tenantId) {
        this.id = id;
        this.ledgerAccountId = ledgerAccountId;
        this.amount = amount;
        this.currency = currency;
        this.tenantId = tenantId;
    }

    public String getId() { return id; }
    public JournalEntryEntity getJournalEntry() { return journalEntry; }
    public String getLedgerAccountId() { return ledgerAccountId; }
    public long getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    void setJournalEntry(JournalEntryEntity journalEntry) {
        this.journalEntry = journalEntry;
    }
}
