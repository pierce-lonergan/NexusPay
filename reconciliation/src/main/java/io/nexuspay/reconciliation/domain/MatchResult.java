package io.nexuspay.reconciliation.domain;

/**
 * Result of matching a settlement record against payment and ledger data.
 *
 * @since 0.2.0 (Sprint 2.3)
 */
public record MatchResult(
        Status status,
        String settlementRecordId,
        String matchedPaymentId,
        String matchedJournalEntryId,
        ExceptionType exceptionType,
        String description
) {

    public enum Status {
        MATCHED,
        PARTIAL,
        UNMATCHED,
        EXCEPTION
    }

    public enum ExceptionType {
        AMOUNT_MISMATCH,
        MISSING_PAYMENT,
        MISSING_SETTLEMENT,
        MISSING_LEDGER_ENTRY,
        FEE_DISCREPANCY,
        CURRENCY_MISMATCH,
        DUPLICATE_SETTLEMENT
    }

    public static MatchResult matched(String settlementId, String paymentId, String journalEntryId) {
        return new MatchResult(Status.MATCHED, settlementId, paymentId, journalEntryId, null, null);
    }

    public static MatchResult partial(String settlementId, String paymentId) {
        // A matched payment with no ledger entry is a genuine books-missing-money
        // discrepancy — it carries a MISSING_LEDGER_ENTRY type so it becomes a
        // tracked exception, not a silently-dropped "unmatched" (B-008).
        return new MatchResult(Status.PARTIAL, settlementId, paymentId, null,
                ExceptionType.MISSING_LEDGER_ENTRY,
                "Payment matched but no ledger entry found");
    }

    public static MatchResult unmatched(String settlementId) {
        return new MatchResult(Status.UNMATCHED, settlementId, null, null,
                ExceptionType.MISSING_PAYMENT, "No matching payment found");
    }

    public static MatchResult exception(ExceptionType type, String settlementId,
                                         String paymentId, String description) {
        return new MatchResult(Status.EXCEPTION, settlementId, paymentId, null, type, description);
    }

    public boolean isSuccessful() {
        return status == Status.MATCHED;
    }
}
