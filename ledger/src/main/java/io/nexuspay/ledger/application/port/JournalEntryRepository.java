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

    boolean existsByPaymentReferenceAndDescription(String paymentReference, String description);
}
