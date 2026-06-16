package io.nexuspay.ledger.adapter.out.persistence;

import io.nexuspay.ledger.application.port.JournalEntryRepository;
import io.nexuspay.ledger.domain.JournalEntry;
import io.nexuspay.ledger.domain.Posting;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JournalEntryRepositoryAdapter implements JournalEntryRepository {

    private final JpaJournalEntryRepository jpaRepository;

    public JournalEntryRepositoryAdapter(JpaJournalEntryRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<JournalEntry> findById(String id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public List<JournalEntry> findByPaymentReference(String paymentReference) {
        return jpaRepository.findByPaymentReference(paymentReference).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public List<JournalEntry> findByPaymentReferenceAndTenantId(String paymentReference, String tenantId) {
        return jpaRepository.findByPaymentReferenceAndTenantId(paymentReference, tenantId).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public List<JournalEntry> findByDateRange(Instant from, Instant to, int limit, int offset, String tenantId) {
        var pageable = PageRequest.of(offset / Math.max(limit, 1), limit);
        return jpaRepository.findByDateRange(from, to, tenantId, pageable).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public JournalEntry save(JournalEntry journalEntry) {
        var entity = toEntity(journalEntry);
        var saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public JournalEntry saveAndFlush(JournalEntry journalEntry) {
        // SEC-10: saveAndFlush (inherited from JpaRepository) forces the INSERT + constraint check at
        // this call, so a uq_journal_entries_payment_ref_desc violation surfaces synchronously inside
        // the use-case try/catch rather than deferred to commit (where it would propagate to the
        // Kafka consumer -> retry/DLT). Mirrors FraudAssessmentService's saveAndFlush race backstop.
        var entity = toEntity(journalEntry);
        var saved = jpaRepository.saveAndFlush(entity);
        return toDomain(saved);
    }

    @Override
    public boolean existsByPaymentReferenceAndDescription(String paymentReference, String description) {
        return jpaRepository.existsByPaymentReferenceAndDescription(paymentReference, description);
    }

    private JournalEntry toDomain(JournalEntryEntity entity) {
        List<Posting> postings = entity.getPostings().stream()
                .map(p -> new Posting(p.getId(), p.getLedgerAccountId(), p.getAmount(), p.getCurrency()))
                .toList();

        return new JournalEntry(
                entity.getId(),
                entity.getPaymentReference(),
                entity.getDescription(),
                entity.getTenantId(),
                entity.getPostedAt(),
                entity.getMetadata(),
                postings
        );
    }

    private JournalEntryEntity toEntity(JournalEntry domain) {
        var entity = new JournalEntryEntity(
                domain.getId(),
                domain.getPaymentReference(),
                domain.getDescription(),
                domain.getTenantId(),
                domain.getPostedAt(),
                domain.getMetadata()
        );

        for (Posting posting : domain.getPostings()) {
            var postingEntity = new PostingEntity(
                    posting.id(),
                    posting.ledgerAccountId(),
                    posting.amount(),
                    posting.currency(),
                    domain.getTenantId()
            );
            entity.addPosting(postingEntity);
        }

        return entity;
    }
}
