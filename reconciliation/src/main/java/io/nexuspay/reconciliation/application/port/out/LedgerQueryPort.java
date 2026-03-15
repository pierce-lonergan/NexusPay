package io.nexuspay.reconciliation.application.port.out;

import java.util.Optional;

/**
 * Port for querying ledger entries during reconciliation matching.
 *
 * @since 0.2.0 (Sprint 2.3)
 */
public interface LedgerQueryPort {

    /**
     * Finds a journal entry by the associated payment reference.
     *
     * @param paymentId the NexusPay payment ID
     * @return ledger record if a journal entry exists for this payment
     */
    Optional<LedgerRecord> findByPaymentReference(String paymentId);

    /**
     * Lightweight DTO for ledger data needed during reconciliation matching.
     */
    record LedgerRecord(
            String journalEntryId,
            String paymentReference,
            long debitAmount,
            long creditAmount,
            String currency
    ) {}
}
