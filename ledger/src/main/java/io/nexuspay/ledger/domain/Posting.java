package io.nexuspay.ledger.domain;

import java.util.Objects;

/**
 * A single debit or credit line within a journal entry.
 * Convention: positive amount = debit, negative amount = credit.
 * Postings are immutable once created.
 */
public record Posting(
        String id,
        String ledgerAccountId,
        long amount,
        String currency
) {
    public Posting {
        Objects.requireNonNull(id, "posting id must not be null");
        Objects.requireNonNull(ledgerAccountId, "ledgerAccountId must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (amount == 0) {
            throw new IllegalArgumentException("Posting amount must not be zero");
        }
    }

    public boolean isDebit() {
        return amount > 0;
    }

    public boolean isCredit() {
        return amount < 0;
    }
}
