package io.nexuspay.common.event;

/**
 * Central registry of all event type constants used across modules.
 * Prevents magic strings and provides a single source of truth.
 */
public final class EventTypes {

    private EventTypes() {}

    // Payment lifecycle
    public static final String PAYMENT_CREATED = "PaymentCreated";
    public static final String PAYMENT_AUTHORIZED = "PaymentAuthorized";
    public static final String PAYMENT_CAPTURED = "PaymentCaptured";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String PAYMENT_VOIDED = "PaymentVoided";

    // Refund lifecycle
    public static final String REFUND_CREATED = "RefundCreated";
    public static final String REFUND_COMPLETED = "RefundCompleted";
    public static final String REFUND_FAILED = "RefundFailed";

    // Ledger
    public static final String LEDGER_ENTRY_CREATED = "LedgerEntryCreated";

    // Aggregate types
    public static final String AGGREGATE_PAYMENT = "Payment";
    public static final String AGGREGATE_REFUND = "Refund";
    public static final String AGGREGATE_LEDGER = "Ledger";
}
