package io.nexuspay.ledger.application.port;

import io.nexuspay.ledger.domain.JournalEntry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for journal entry persistence.
 */
public interface JournalEntryRepository {

    Optional<JournalEntry> findById(String id);

    List<JournalEntry> findByPaymentReference(String paymentReference);

    List<JournalEntry> findByDateRange(Instant from, Instant to, int limit, int offset);

    JournalEntry save(JournalEntry journalEntry);

    /**
     * SEC-10: flushing variant of {@link #save}. JournalEntryEntity has a pre-assigned String
     * {@code @Id} (no {@code @GeneratedValue}/{@code @Version}, not {@code Persistable}), so a plain
     * {@code save} routes through {@code em.merge()} and DEFERS the INSERT to flush/commit — the
     * {@code uq_journal_entries_payment_ref_desc} unique-violation would then surface OUTSIDE the
     * use-case try/catch (at commit) and propagate to the Kafka consumer (retry/DLT). saveAndFlush
     * forces the INSERT (and the constraint check) synchronously so the dup-key no-op can catch it.
     */
    JournalEntry saveAndFlush(JournalEntry journalEntry);

    boolean existsByPaymentReferenceAndDescription(String paymentReference, String description);
}
