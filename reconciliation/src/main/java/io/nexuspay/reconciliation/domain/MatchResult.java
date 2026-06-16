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
        DUPLICATE_SETTLEMENT,
        // B-015: a settlement row that could not be parsed or validated during
        // ingestion (RFC-4180 corruption, non-numeric amount, bad date, ragged
        // columns). Persisted as a durable exception so no row is silently
        // dropped. 11 chars — fits the VARCHAR(32) exception_type column.
        PARSE_ERROR,
        // SEC-17: the reconciliation run itself failed (matching/persistence threw
        // mid-pipeline). Recorded as a durable failure-reason exception by
        // ReconciliationFailureRecorder in a REQUIRES_NEW transaction so the failure
        // survives the outer rollback. 12 chars — fits the VARCHAR(32)
        // exception_type column (V4021 only COMMENTs the column; it is not
        // check-constrained).
        SYSTEM_ERROR
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
