package io.nexuspay.ledger.application;

import io.nexuspay.common.exception.LedgerException;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.ledger.application.port.JournalEntryRepository;
import io.nexuspay.ledger.application.port.LedgerAccountRepository;
import io.nexuspay.ledger.domain.JournalEntry;
import io.nexuspay.ledger.domain.Posting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
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

        // Persist the journal entry and postings.
        //
        // SEC-10 race backstop: PaymentEventConsumer guards with a check-then-act
        // existsByPaymentReferenceAndDescription read (the cheap fast path), but a concurrent Kafka
        // redelivery / DLT replay can slip past it (both reads miss, both attempt the INSERT). The
        // UNIQUE index uq_journal_entries_payment_ref_desc on (payment_reference, description) (V4028)
        // is the backstop the read cannot close. saveAndFlush (NOT save) forces the INSERT and the
        // constraint check synchronously HERE, so the loser's violation surfaces inside this try/catch
        // rather than deferred to commit (where it would escape past the consumer's logging at
        // PaymentEventConsumer L114/L153, propagate as a RuntimeException, and re-enter retry -> DLT).
        // Mirrors the fraud saveAndFlush idiom (FraudAssessmentService L205-224, L334-345).
        try {
            JournalEntry saved = journalEntryRepository.saveAndFlush(journalEntry);
            log.info("Created journal entry {} with {} postings for payment {}",
                    saved.getId(), postings.size(), command.paymentReference());
            return saved;
        } catch (DataIntegrityViolationException dup) {
            // Narrow the catch to OUR idempotency constraint only. A genuine/unrelated integrity error
            // (FK to a missing ledger account, NOT-NULL, a different constraint) must NOT be silently
            // swallowed as a benign duplicate — re-throw so it propagates to retry/DLT and is not masked.
            if (!isJournalIdemConstraintViolation(dup)) {
                throw dup;
            }
            // Concurrent redelivery: the UNIQUE index rejected this duplicate; the row that won is
            // already persisted by the other writer. The failed flush has marked this SERIALIZABLE tx
            // rollback-only, so the loser's updateAccountBalance increments (applied above, BEFORE this
            // flush) roll back together with the duplicate row — the balance is NOT double-credited.
            // We return a deterministic locally-built no-op result (NOT a throw) so the consumer does
            // NOT re-enter retry/DLT; the existsBy fast path on the NEXT redelivery serves the read.
            // No re-query here: the tx is rollback-only, so a follow-up read would hit an aborted tx.
            log.info("Concurrent redelivery for paymentRef={}, desc={} — unique index "
                    + "(uq_journal_entries_payment_ref_desc) rejected the duplicate; no double-post",
                    command.paymentReference(), command.description(), dup);
            return journalEntry;
        }
    }

    /** Name of the unique index that enforces (payment_reference, description) idempotency (V4028). */
    private static final String JOURNAL_IDEM_CONSTRAINT = "uq_journal_entries_payment_ref_desc";

    /**
     * SEC-10: narrow the dup-key no-op to the SPECIFIC (payment_reference, description) uniqueness race
     * so an unrelated integrity error is not swallowed as a benign duplicate. Two complementary signals
     * (mirrors fraud isTenantIdemConstraintViolation):
     * <ul>
     *   <li>Spring's {@link DuplicateKeyException} — the dedicated duplicate-key subtype the JPA
     *       exception translator raises for a unique violation (PostgreSQL SQLSTATE 23505); or</li>
     *   <li>the {@link #JOURNAL_IDEM_CONSTRAINT} name appearing anywhere in the exception chain.</li>
     * </ul>
     * Any other {@link DataIntegrityViolationException} returns {@code false} and is re-thrown.
     */
    private static boolean isJournalIdemConstraintViolation(DataIntegrityViolationException ex) {
        if (ex instanceof DuplicateKeyException) {
            return true;
        }
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains(JOURNAL_IDEM_CONSTRAINT)) {
                return true;
            }
        }
        return false;
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
