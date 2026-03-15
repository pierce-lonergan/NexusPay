package io.nexuspay.common.event;

/**
 * Central registry of all Kafka topic names.
 * Domain-level topics (not per-event). Event type filtering via the event_type field.
 */
public final class Topics {

    private Topics() {}

    /** All payment and refund lifecycle events. Partition key: payment_id or refund_id. */
    public static final String PAYMENTS = "nexuspay.payments";

    /** All ledger events. Partition key: journal_entry_id. */
    public static final String LEDGER = "nexuspay.ledger";

    /** Dead letter topic for payment events. */
    public static final String PAYMENTS_DLT = "nexuspay.payments.DLT";

    /** Dead letter topic for ledger events. */
    public static final String LEDGER_DLT = "nexuspay.ledger.DLT";

    /** All billing lifecycle events (subscriptions, invoices, dunning). Partition key: subscription_id or invoice_id. */
    public static final String BILLING = "nexuspay.billing";

    /** Dead letter topic for billing events. */
    public static final String BILLING_DLT = "nexuspay.billing.DLT";

    // Consumer group IDs
    public static final String LEDGER_CONSUMER_GROUP = "nexuspay-ledger-consumer";
    public static final String GATEWAY_CONSUMER_GROUP = "nexuspay-gateway-consumer";
    public static final String BILLING_CONSUMER_GROUP = "nexuspay-billing-consumer";
}
