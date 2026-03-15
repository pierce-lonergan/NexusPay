package io.nexuspay.ledger.application;

import io.nexuspay.common.exception.LedgerException;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.ledger.application.port.JournalEntryRepository;
import io.nexuspay.ledger.application.port.LedgerAccountRepository;
import io.nexuspay.ledger.domain.JournalEntry;
import io.nexuspay.ledger.domain.Posting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Creates a balanced journal entry with SERIALIZABLE isolation.
 * Updates posted_balance on each affected ledger account with optimistic concurrency.
 */
@Service
public class CreateJournalEntryUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateJournalEntryUseCase.class);
    private static final int MAX_OPTIMISTIC_RETRIES = 3;

    private final JournalEntryRepository journalEntryRepository;
    private final LedgerAccountRepository ledgerAccountRepository;

    public CreateJournalEntryUseCase(JournalEntryRepository journalEntryRepository,
                                     LedgerAccountRepository ledgerAccountRepository) {
        this.journalEntryRepository = journalEntryRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public JournalEntry execute(CreateJournalEntryCommand command) {
        // Build postings with generated IDs
        List<Posting> postings = command.postings().stream()
                .map(p -> new Posting(
                        PrefixedId.posting(),
                        p.ledgerAccountId(),
                        p.amount(),
                        p.currency()
                ))
                .toList();

        // Create journal entry (validates zero-sum in constructor)
        var journalEntry = new JournalEntry(
                PrefixedId.journalEntry(),
                command.paymentReference(),
                command.description(),
                command.tenantId(),
                Instant.now(),
                command.metadata(),
                postings
        );

        // Update balance on each affected account
        for (Posting posting : postings) {
            updateAccountBalance(posting.ledgerAccountId(), posting.amount());
        }

        // Persist the journal entry and postings
        JournalEntry saved = journalEntryRepository.save(journalEntry);
        log.info("Created journal entry {} with {} postings for payment {}",
                saved.getId(), postings.size(), command.paymentReference());

        return saved;
    }

    private void updateAccountBalance(String accountId, long amount) {
        for (int attempt = 0; attempt < MAX_OPTIMISTIC_RETRIES; attempt++) {
            var account = ledgerAccountRepository.findById(accountId)
                    .orElseThrow(() -> LedgerException.accountNotFound(accountId));

            long newBalance = account.getPostedBalance() + amount;
            boolean updated = ledgerAccountRepository.updateBalanceWithVersion(
                    accountId, newBalance, account.getVersion());

            if (updated) {
                return;
            }
            log.warn("Optimistic lock conflict on account {}, attempt {}", accountId, attempt + 1);
        }
        throw LedgerException.concurrencyConflict(accountId);
    }

    /**
     * Command object for creating a journal entry.
     */
    public record CreateJournalEntryCommand(
            String paymentReference,
            String description,
            String tenantId,
            Map<String, Object> metadata,
            List<PostingLine> postings
    ) {
        public record PostingLine(
                String ledgerAccountId,
                long amount,
                String currency
        ) {}
    }
}
