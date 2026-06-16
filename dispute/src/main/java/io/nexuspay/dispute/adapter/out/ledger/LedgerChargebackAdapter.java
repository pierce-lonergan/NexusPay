package io.nexuspay.dispute.adapter.out.ledger;

import io.nexuspay.dispute.application.port.out.LedgerPort;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand.PostingLine;
import io.nexuspay.ledger.application.EnsureAccountsExistUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Implements {@link LedgerPort} by booking chargeback journal entries through
 * the ledger module's {@link CreateJournalEntryUseCase}.
 *
 * <p>Without this bean the {@code @Service DisputeLifecycleService} has an
 * unsatisfied dependency and the whole Spring context fails to start.</p>
 *
 * @since 0.2.4 (Sprint 2.4)
 */
@Component
public class LedgerChargebackAdapter implements LedgerPort {

    private static final Logger log = LoggerFactory.getLogger(LedgerChargebackAdapter.class);

    private final EnsureAccountsExistUseCase ensureAccounts;
    private final CreateJournalEntryUseCase createJournalEntry;

    public LedgerChargebackAdapter(EnsureAccountsExistUseCase ensureAccounts,
                                   CreateJournalEntryUseCase createJournalEntry) {
        this.ensureAccounts = ensureAccounts;
        this.createJournalEntry = createJournalEntry;
    }

    @Override
    public void createChargebackReserve(String tenantId, String disputeId, long amount, String currency) {
        // DR Chargeback Reserve, CR Merchant Receivables — hold the disputed funds.
        post(tenantId, disputeId, "Chargeback reserve", currency,
                EnsureAccountsExistUseCase.chargebackReserveId(currency), amount,
                EnsureAccountsExistUseCase.merchantReceivablesId(currency), -amount);
    }

    @Override
    public void reverseChargebackReserve(String tenantId, String disputeId, long amount, String currency) {
        // DR Merchant Receivables, CR Chargeback Reserve — release the hold (won).
        post(tenantId, disputeId, "Chargeback reserve reversed", currency,
                EnsureAccountsExistUseCase.merchantReceivablesId(currency), amount,
                EnsureAccountsExistUseCase.chargebackReserveId(currency), -amount);
    }

    @Override
    public void finaliseChargebackExpense(String tenantId, String disputeId, long amount, String currency) {
        // DR Chargeback Expense, CR Chargeback Reserve — book the loss (lost/expired).
        post(tenantId, disputeId, "Chargeback expense", currency,
                EnsureAccountsExistUseCase.chargebackExpenseId(currency), amount,
                EnsureAccountsExistUseCase.chargebackReserveId(currency), -amount);
    }

    private void post(String tenantId, String disputeId, String description, String currency,
                      String debitAccount, long debitAmount,
                      String creditAccount, long creditAmount) {
        String ccy = currency.toUpperCase();
        // Scope account stamping + the journal entry/postings to the dispute's
        // server-authoritative tenant (SEC-24). The tenant-aware overload coalesces
        // a null/blank tenant to DEFAULT_TENANT; CreateJournalEntryCommand carries
        // tenantId as-is (guaranteed non-blank by the webhook gate + Dispute.open).
        ensureAccounts.ensureAccountsForCurrency(ccy, tenantId);

        var command = new CreateJournalEntryCommand(
                disputeId,
                description,
                tenantId,
                Map.of("dispute_id", disputeId, "type", "chargeback"),
                List.of(
                        new PostingLine(debitAccount, debitAmount, ccy),
                        new PostingLine(creditAccount, creditAmount, ccy)
                )
        );

        var entry = createJournalEntry.execute(command);
        log.info("Chargeback ledger entry {} booked: {} {} {} for dispute {}",
                entry.getId(), description, debitAmount, ccy, disputeId);
    }
}
