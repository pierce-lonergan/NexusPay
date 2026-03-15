package io.nexuspay.common;

import io.nexuspay.common.id.PrefixedId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrefixedIdTest {

    @Test
    void generatesPaymentIntentPrefix() {
        String id = PrefixedId.paymentIntent();
        assertTrue(id.startsWith("pi_"));
        assertEquals(35, id.length()); // "pi_" + 32 hex chars
    }

    @Test
    void generatesUniqueIds() {
        String a = PrefixedId.paymentIntent();
        String b = PrefixedId.paymentIntent();
        assertNotEquals(a, b);
    }

    @Test
    void allPrefixesWork() {
        assertTrue(PrefixedId.charge().startsWith("ch_"));
        assertTrue(PrefixedId.refund().startsWith("ref_"));
        assertTrue(PrefixedId.ledgerAccount().startsWith("la_"));
        assertTrue(PrefixedId.journalEntry().startsWith("je_"));
        assertTrue(PrefixedId.posting().startsWith("post_"));
        assertTrue(PrefixedId.webhook().startsWith("wh_"));
        assertTrue(PrefixedId.event().startsWith("evt_"));
        assertTrue(PrefixedId.approval().startsWith("apr_"));
    }
}
