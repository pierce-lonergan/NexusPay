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

    // Consumer group IDs
    public static final String LEDGER_CONSUMER_GROUP = "nexuspay-ledger-consumer";
    public static final String GATEWAY_CONSUMER_GROUP = "nexuspay-gateway-consumer";
}
