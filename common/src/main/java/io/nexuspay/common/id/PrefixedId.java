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
    public static final String WEBHOOK_DELIVERY = "whd_";
    public static final String RECONCILIATION_RUN = "rec_";
    public static final String SETTLEMENT_RECORD = "stl_";
    public static final String RECONCILIATION_EXCEPTION = "rex_";
    public static final String DISPUTE = "dp_";
    public static final String DISPUTE_EVIDENCE = "dpe_";
    public static final String DISPUTE_EVENT = "dpev_";
    public static final String PRODUCT = "prod_";
    public static final String PRICE = "price_";
    public static final String SUBSCRIPTION = "sub_";
    public static final String INVOICE = "inv_";
    public static final String INVOICE_LINE_ITEM = "ili_";
    public static final String DUNNING_ATTEMPT = "dun_";
    public static final String PAYMENT_SESSION = "ps_";
    public static final String PAYMENT_TOKEN = "ptok_";
    public static final String CUSTOMER = "cus_";
    public static final String MANDATE = "mandate_";

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

    public static String webhookDelivery() {
        return generate(WEBHOOK_DELIVERY);
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

    public static String dispute() {
        return generate(DISPUTE);
    }

    public static String disputeEvidence() {
        return generate(DISPUTE_EVIDENCE);
    }

    public static String disputeEvent() {
        return generate(DISPUTE_EVENT);
    }

    public static String product() {
        return generate(PRODUCT);
    }

    public static String price() {
        return generate(PRICE);
    }

    public static String subscription() {
        return generate(SUBSCRIPTION);
    }

    public static String invoice() {
        return generate(INVOICE);
    }

    public static String invoiceLineItem() {
        return generate(INVOICE_LINE_ITEM);
    }

    public static String dunningAttempt() {
        return generate(DUNNING_ATTEMPT);
    }

    public static String paymentSession() {
        return generate(PAYMENT_SESSION);
    }

    public static String paymentToken() {
        return generate(PAYMENT_TOKEN);
    }

    public static String customer() {
        return generate(CUSTOMER);
    }

    public static String mandate() {
        return generate(MANDATE);
    }
}
