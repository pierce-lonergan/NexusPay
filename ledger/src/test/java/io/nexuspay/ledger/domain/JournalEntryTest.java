package io.nexuspay.ledger.domain;

import io.nexuspay.common.exception.LedgerException;
import io.nexuspay.common.id.PrefixedId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JournalEntryTest {

    @Test
    void balancedEntryCreatesSuccessfully() {
        var postings = List.of(
                new Posting(PrefixedId.posting(), "la_merchant_recv_usd", 10000, "USD"),
                new Posting(PrefixedId.posting(), "la_customer_liab_usd", -10000, "USD")
        );

        var entry = new JournalEntry(
                PrefixedId.journalEntry(), "pi_test123", "Payment captured",
                "default", Instant.now(), Map.of(), postings
        );

        assertEquals(2, entry.getPostings().size());
        assertEquals("pi_test123", entry.getPaymentReference());
    }

    @Test
    void unbalancedEntryThrowsException() {
        var postings = List.of(
                new Posting(PrefixedId.posting(), "la_merchant_recv_usd", 10000, "USD"),
                new Posting(PrefixedId.posting(), "la_customer_liab_usd", -5000, "USD")
        );

        var ex = assertThrows(LedgerException.class, () ->
                new JournalEntry(
                        PrefixedId.journalEntry(), "pi_test", "Bad entry",
                        "default", Instant.now(), Map.of(), postings
                )
        );
        assertTrue(ex.getMessage().contains("do not balance"));
    }

    @Test
    void singlePostingThrowsException() {
        var postings = List.of(
                new Posting(PrefixedId.posting(), "la_merchant_recv_usd", 0, "USD")
        );

        // Zero amount posting is rejected by Posting constructor
        assertThrows(IllegalArgumentException.class, () ->
                new Posting(PrefixedId.posting(), "la_merchant_recv_usd", 0, "USD")
        );
    }

    @Test
    void minimumTwoPostingsRequired() {
        assertThrows(IllegalArgumentException.class, () ->
                new JournalEntry(
                        PrefixedId.journalEntry(), "pi_test", "Bad",
                        "default", Instant.now(), Map.of(), List.of()
                )
        );
    }

    @Test
    void multiLegEntryBalances() {
        // Payment with fee: DR merchant 9700, DR fees 300, CR customer -10000
        var postings = List.of(
                new Posting(PrefixedId.posting(), "la_merchant_recv_usd", 9700, "USD"),
                new Posting(PrefixedId.posting(), "la_processing_fees_usd", 300, "USD"),
                new Posting(PrefixedId.posting(), "la_customer_liab_usd", -10000, "USD")
        );

        var entry = new JournalEntry(
                PrefixedId.journalEntry(), "pi_with_fee", "Payment with processing fee",
                "default", Instant.now(), Map.of(), postings
        );

        assertEquals(3, entry.getPostings().size());
        long sum = entry.getPostings().stream().mapToLong(Posting::amount).sum();
        assertEquals(0, sum);
    }

    @Test
    void postingsAreImmutable() {
        var postings = new java.util.ArrayList<>(List.of(
                new Posting(PrefixedId.posting(), "la_merchant_recv_usd", 10000, "USD"),
                new Posting(PrefixedId.posting(), "la_customer_liab_usd", -10000, "USD")
        ));

        var entry = new JournalEntry(
                PrefixedId.journalEntry(), "pi_test", "Test",
                "default", Instant.now(), Map.of(), postings
        );

        assertThrows(UnsupportedOperationException.class, () ->
                entry.getPostings().add(
                        new Posting(PrefixedId.posting(), "la_test", 100, "USD")
                )
        );
    }
}
