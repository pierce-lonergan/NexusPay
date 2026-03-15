package io.nexuspay.ledger.domain;

import io.nexuspay.common.exception.LedgerException;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable journal entry — the aggregate root for double-entry postings.
 * Enforces the zero-sum invariant: SUM(posting amounts) must equal 0.
 */
public class JournalEntry {

    private final String id;
    private final String paymentReference;
    private final String description;
    private final String tenantId;
    private final Instant postedAt;
    private final Map<String, Object> metadata;
    private final List<Posting> postings;

    public JournalEntry(String id, String paymentReference, String description,
                        String tenantId, Instant postedAt, Map<String, Object> metadata,
                        List<Posting> postings) {
        this.id = Objects.requireNonNull(id);
        this.paymentReference = paymentReference;
        this.description = description;
        this.tenantId = Objects.requireNonNull(tenantId);
        this.postedAt = postedAt != null ? postedAt : Instant.now();
        this.metadata = metadata;
        this.postings = List.copyOf(Objects.requireNonNull(postings));

        validateZeroSum();
        validateMinimumPostings();
    }

    /**
     * Core invariant: all postings must sum to zero (balanced entry).
     */
    private void validateZeroSum() {
        long sum = postings.stream().mapToLong(Posting::amount).sum();
        if (sum != 0) {
            throw LedgerException.unbalancedEntry(sum);
        }
    }

    private void validateMinimumPostings() {
        if (postings.size() < 2) {
            throw new IllegalArgumentException("Journal entry must have at least 2 postings");
        }
    }

    public String getId() { return id; }
    public String getPaymentReference() { return paymentReference; }
    public String getDescription() { return description; }
    public String getTenantId() { return tenantId; }
    public Instant getPostedAt() { return postedAt; }
    public Map<String, Object> getMetadata() { return metadata; }
    public List<Posting> getPostings() { return Collections.unmodifiableList(postings); }
}
