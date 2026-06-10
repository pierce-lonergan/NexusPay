package io.nexuspay.reconciliation.adapter.out.query;

import io.nexuspay.ledger.application.port.JournalEntryRepository;
import io.nexuspay.ledger.domain.JournalEntry;
import io.nexuspay.ledger.domain.Posting;
import io.nexuspay.reconciliation.application.port.out.PaymentQueryPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Implements {@link PaymentQueryPort} by inferring payment existence from the
 * ledger: in NexusPay a captured payment always books a journal entry whose
 * {@code paymentReference} is the payment id, so a present journal entry is
 * evidence the payment was processed.
 *
 * <p>The reconciliation module cannot depend on {@code payment-orchestration}
 * (Modulith boundary: {@code allowedDependencies = common, ledger}) and there is
 * no payment read-model exposed to it, so this is the maximum fidelity available
 * here — effectively a settlement↔ledger two-way match. It assumes the settlement
 * file's {@code externalId} carries the NexusPay payment id. A future
 * payment-read-model (event-sourced view) would replace this adapter to enable a
 * true three-way match against PSP references.</p>
 *
 * <p>Without this bean the {@code @Service ThreeWayMatchingService} has an
 * unsatisfied dependency and the whole Spring context fails to start.</p>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Component
public class LedgerBackedPaymentQueryAdapter implements PaymentQueryPort {

    private final JournalEntryRepository journalEntryRepository;

    public LedgerBackedPaymentQueryAdapter(JournalEntryRepository journalEntryRepository) {
        this.journalEntryRepository = journalEntryRepository;
    }

    @Override
    public Optional<PaymentRecord> findByExternalRef(String externalRef, String provider) {
        List<JournalEntry> entries = journalEntryRepository.findByPaymentReference(externalRef);
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        JournalEntry entry = entries.get(0);

        long debit = 0;
        String currency = null;
        for (Posting posting : entry.getPostings()) {
            if (currency == null) {
                currency = posting.currency();
            }
            if (posting.amount() >= 0) {
                debit += posting.amount();
            }
        }

        return Optional.of(new PaymentRecord(
                entry.getPaymentReference(),
                externalRef,
                debit,
                currency,
                "CAPTURED",
                provider));
    }
}
