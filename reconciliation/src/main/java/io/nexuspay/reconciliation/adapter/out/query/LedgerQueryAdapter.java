package io.nexuspay.reconciliation.adapter.out.query;

import io.nexuspay.ledger.application.port.JournalEntryRepository;
import io.nexuspay.ledger.domain.JournalEntry;
import io.nexuspay.ledger.domain.Posting;
import io.nexuspay.reconciliation.application.port.out.LedgerQueryPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Implements {@link LedgerQueryPort} against the ledger module's
 * {@link JournalEntryRepository}.
 *
 * <p>Without this bean the {@code @Service ThreeWayMatchingService} has an
 * unsatisfied dependency and the whole Spring context fails to start.</p>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Component
public class LedgerQueryAdapter implements LedgerQueryPort {

    private final JournalEntryRepository journalEntryRepository;

    public LedgerQueryAdapter(JournalEntryRepository journalEntryRepository) {
        this.journalEntryRepository = journalEntryRepository;
    }

    @Override
    public Optional<LedgerRecord> findByPaymentReference(String paymentId) {
        List<JournalEntry> entries = journalEntryRepository.findByPaymentReference(paymentId);
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        // A captured payment books one balanced entry; if several exist, the
        // first (capture) entry is the one settlement reconciles against.
        return Optional.of(toRecord(entries.get(0)));
    }

    private LedgerRecord toRecord(JournalEntry entry) {
        long debit = 0;
        long credit = 0;
        String currency = null;
        for (Posting posting : entry.getPostings()) {
            if (currency == null) {
                currency = posting.currency();
            }
            if (posting.amount() >= 0) {
                debit += posting.amount();
            } else {
                credit += -posting.amount();
            }
        }
        return new LedgerRecord(entry.getId(), entry.getPaymentReference(), debit, credit, currency);
    }
}
