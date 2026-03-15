package io.nexuspay.ledger.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LedgerAccountTest {

    @Test
    void applyPostingUpdatesBalance() {
        var account = new LedgerAccount(
                "la_test", "Test Account", AccountType.ASSET, "USD",
                0L, 0L, "default", Instant.now(), Instant.now()
        );

        account.applyPosting(10000);
        assertEquals(10000, account.getPostedBalance());
        assertEquals(1, account.getVersion());
    }

    @Test
    void applyMultiplePostings() {
        var account = new LedgerAccount(
                "la_test", "Test Account", AccountType.ASSET, "USD",
                5000L, 0L, "default", Instant.now(), Instant.now()
        );

        account.applyPosting(3000);
        account.applyPosting(-1000);

        assertEquals(7000, account.getPostedBalance());
        assertEquals(2, account.getVersion());
    }

    @Test
    void negativeBalanceAllowed() {
        var account = new LedgerAccount(
                "la_test", "Test Account", AccountType.LIABILITY, "USD",
                0L, 0L, "default", Instant.now(), Instant.now()
        );

        account.applyPosting(-50000);
        assertEquals(-50000, account.getPostedBalance());
    }
}
