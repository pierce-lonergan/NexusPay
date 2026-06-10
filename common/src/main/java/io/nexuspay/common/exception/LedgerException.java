package io.nexuspay.common.exception;

public class LedgerException extends NexusPayException {

    public LedgerException(String message, String errorCode) {
        super(message, errorCode);
    }

    public LedgerException(String message, String errorCode, Throwable cause) {
        super(message, errorCode, cause);
    }

    public static LedgerException unbalancedEntry(long sum) {
        return new LedgerException(
                "Journal entry postings do not balance. Sum: " + sum,
                "unbalanced_entry"
        );
    }

    public static LedgerException unbalancedEntry(String currency, long sum) {
        return new LedgerException(
                "Journal entry postings do not balance for currency " + currency + ". Sum: " + sum,
                "unbalanced_entry"
        );
    }

    public static LedgerException accountNotFound(String accountId) {
        return new LedgerException(
                "Ledger account not found: " + accountId,
                "account_not_found"
        );
    }

    public static LedgerException concurrencyConflict(String accountId) {
        return new LedgerException(
                "Concurrent modification on account: " + accountId,
                "concurrency_conflict"
        );
    }
}
