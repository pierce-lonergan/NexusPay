package io.nexuspay.ledger.domain;

import io.nexuspay.common.id.PrefixedId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostingTest {

    @Test
    void debitPostingIsPositive() {
        var posting = new Posting(PrefixedId.posting(), "la_test", 10000, "USD");
        assertTrue(posting.isDebit());
        assertFalse(posting.isCredit());
    }

    @Test
    void creditPostingIsNegative() {
        var posting = new Posting(PrefixedId.posting(), "la_test", -10000, "USD");
        assertFalse(posting.isDebit());
        assertTrue(posting.isCredit());
    }

    @Test
    void zeroAmountRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new Posting(PrefixedId.posting(), "la_test", 0, "USD")
        );
    }

    @Test
    void nullFieldsRejected() {
        assertThrows(NullPointerException.class, () ->
                new Posting(null, "la_test", 100, "USD")
        );
        assertThrows(NullPointerException.class, () ->
                new Posting(PrefixedId.posting(), null, 100, "USD")
        );
        assertThrows(NullPointerException.class, () ->
                new Posting(PrefixedId.posting(), "la_test", 100, null)
        );
    }
}
