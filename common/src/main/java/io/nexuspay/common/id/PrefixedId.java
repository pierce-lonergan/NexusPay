package io.nexuspay.common.id;

import java.util.UUID;

/**
 * Generates type-prefixed unique identifiers for all NexusPay entities.
 * Prefixed IDs provide instant recognition in logs and debugging.
 *
 * Examples: pi_abc123, ch_def456, ref_ghi789
 */
public final class PrefixedId {

    private PrefixedId() {
    }

    public static final String PAYMENT_INTENT = "pi_";
    public static final String CHARGE = "ch_";
    public static final String REFUND = "ref_";
    public static final String PAYMENT_METHOD = "pm_";
    public static final String LEDGER_ACCOUNT = "la_";
    public static final String JOURNAL_ENTRY = "je_";
    public static final String POSTING = "post_";
    public static final String WEBHOOK = "wh_";
    public static final String EVENT = "evt_";
    public static final String APPROVAL = "apr_";
    public static final String API_KEY = "key_";
    public static final String AUDIT = "aud_";
    public static final String WEBHOOK_ENDPOINT = "we_";
    public static final String RECONCILIATION_RUN = "rec_";
    public static final String SETTLEMENT_RECORD = "stl_";
    public static final String RECONCILIATION_EXCEPTION = "rex_";

    /**
     * Generates a new prefixed ID with a random UUID (hyphens removed).
     */
    public static String generate(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    public static String paymentIntent() {
        return generate(PAYMENT_INTENT);
    }

    public static String charge() {
        return generate(CHARGE);
    }

    public static String refund() {
        return generate(REFUND);
    }

    public static String paymentMethod() {
        return generate(PAYMENT_METHOD);
    }

    public static String ledgerAccount() {
        return generate(LEDGER_ACCOUNT);
    }

    public static String journalEntry() {
        return generate(JOURNAL_ENTRY);
    }

    public static String posting() {
        return generate(POSTING);
    }

    public static String webhook() {
        return generate(WEBHOOK);
    }

    public static String event() {
        return generate(EVENT);
    }

    public static String approval() {
        return generate(APPROVAL);
    }

    public static String apiKey() {
        return generate(API_KEY);
    }

    public static String audit() {
        return generate(AUDIT);
    }

    public static String webhookEndpoint() {
        return generate(WEBHOOK_ENDPOINT);
    }

    public static String reconciliationRun() {
        return generate(RECONCILIATION_RUN);
    }

    public static String settlementRecord() {
        return generate(SETTLEMENT_RECORD);
    }

    public static String reconciliationException() {
        return generate(RECONCILIATION_EXCEPTION);
    }
}
