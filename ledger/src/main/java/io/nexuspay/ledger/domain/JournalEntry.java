package io.nexuspay.ledger.domain;

import io.nexuspay.common.exception.LedgerException;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
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
     * Core invariant: postings must sum to zero <em>within each currency</em>.
     * A single numeric sum across currencies would accept entries like
     * {@code +10000 JPY / -10000 USD}, which are semantically unbalanced.
     */
    private void validateZeroSum() {
        Map<String, Long> sumsByCurrency = new HashMap<>();
        for (Posting posting : postings) {
            sumsByCurrency.merge(posting.currency(), posting.amount(), Long::sum);
        }
        for (var entry : sumsByCurrency.entrySet()) {
            if (entry.getValue() != 0) {
                throw LedgerException.unbalancedEntry(entry.getKey(), entry.getValue());
            }
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
