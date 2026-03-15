package io.nexuspay.ledger.adapter.out.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "journal_entries")
public class JournalEntryEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "payment_reference", length = 64)
    private String paymentReference;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    private List<PostingEntity> postings = new ArrayList<>();

    protected JournalEntryEntity() {}

    public JournalEntryEntity(String id, String paymentReference, String description,
                               String tenantId, Instant postedAt, Map<String, Object> metadata) {
        this.id = id;
        this.paymentReference = paymentReference;
        this.description = description;
        this.tenantId = tenantId;
        this.postedAt = postedAt;
        this.metadata = metadata;
    }

    public void addPosting(PostingEntity posting) {
        postings.add(posting);
        posting.setJournalEntry(this);
    }

    public String getId() { return id; }
    public String getPaymentReference() { return paymentReference; }
    public String getDescription() { return description; }
    public String getTenantId() { return tenantId; }
    public Instant getPostedAt() { return postedAt; }
    public Map<String, Object> getMetadata() { return metadata; }
    public List<PostingEntity> getPostings() { return postings; }
}
