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

    // Subscription billing lifecycle (Sprint 2.5b)
    public static final String SUBSCRIPTION_CREATED = "SubscriptionCreated";
    public static final String SUBSCRIPTION_ACTIVATED = "SubscriptionActivated";
    public static final String SUBSCRIPTION_CANCELED = "SubscriptionCanceled";
    public static final String SUBSCRIPTION_PAUSED = "SubscriptionPaused";
    public static final String SUBSCRIPTION_RESUMED = "SubscriptionResumed";
    public static final String SUBSCRIPTION_RENEWED = "SubscriptionRenewed";
    public static final String SUBSCRIPTION_TRIAL_CONVERTED = "SubscriptionTrialConverted";

    // Invoice lifecycle (Sprint 2.5b)
    public static final String INVOICE_CREATED = "InvoiceCreated";
    public static final String INVOICE_FINALIZED = "InvoiceFinalized";
    public static final String INVOICE_PAID = "InvoicePaid";
    public static final String INVOICE_VOIDED = "InvoiceVoided";
    public static final String INVOICE_UNCOLLECTIBLE = "InvoiceUncollectible";

    // Dunning lifecycle (Sprint 2.5b)
    public static final String DUNNING_INITIATED = "DunningInitiated";
    public static final String DUNNING_RETRY_FAILED = "DunningRetryFailed";
    public static final String DUNNING_RECOVERED = "DunningRecovered";
    public static final String DUNNING_EXHAUSTED = "DunningExhausted";

    // Fraud lifecycle (Sprint 3.1)
    public static final String FRAUD_CHECK_PASSED = "FraudCheckPassed";
    public static final String FRAUD_CHECK_FAILED = "FraudCheckFailed";
    public static final String FRAUD_CHECK_REVIEW = "FraudCheckReview";
    public static final String FRAUD_RULE_TRIGGERED = "RuleTriggered";
    public static final String FRAUD_RULE_CREATED = "FraudRuleCreated";
    public static final String FRAUD_RULE_UPDATED = "FraudRuleUpdated";
    public static final String FRAUD_RULE_DISABLED = "FraudRuleDisabled";
    public static final String FRAUD_REVIEW_APPROVED = "FraudReviewApproved";
    public static final String FRAUD_REVIEW_REJECTED = "FraudReviewRejected";

    // FX lifecycle (Sprint 3.2)
    public static final String FX_RATE_UPDATED = "FxRateUpdated";
    public static final String FX_RATE_LOCKED = "FxRateLocked";
    public static final String FX_RATE_LOCK_EXPIRED = "FxRateLockExpired";
    public static final String FX_RATE_LOCK_CONSUMED = "FxRateLockConsumed";
    public static final String CURRENCY_CONVERSION_COMPLETED = "CurrencyConversionCompleted";
    public static final String CROSS_BORDER_COMPLIANCE_BLOCKED = "CrossBorderComplianceBlocked";
    public static final String CROSS_BORDER_REPORTING_REQUIRED = "CrossBorderReportingRequired";

    // Routing lifecycle (Sprint 3.3)
    public static final String ROUTE_SELECTED = "RouteSelected";
    public static final String ROUTE_FAILED = "RouteFailed";
    public static final String CASCADE_TRIGGERED = "CascadeTriggered";

    // Marketplace lifecycle (Sprint 4.2)
    public static final String ACCOUNT_ONBOARDED = "AccountOnboarded";
    public static final String ACCOUNT_UPDATED = "AccountUpdated";
    public static final String ACCOUNT_SUSPENDED = "AccountSuspended";
    public static final String ACCOUNT_CLOSED = "AccountClosed";
    public static final String SPLIT_PAYMENT_CREATED = "SplitPaymentCreated";
    public static final String PAYOUT_CREATED = "PayoutCreated";
    public static final String PAYOUT_PAID = "PayoutPaid";
    public static final String PAYOUT_FAILED = "PayoutFailed";
    public static final String FEE_CONFIGURED = "FeeConfigured";

    // Aggregate types
    public static final String AGGREGATE_PAYMENT = "Payment";
    public static final String AGGREGATE_REFUND = "Refund";
    public static final String AGGREGATE_LEDGER = "Ledger";
    public static final String AGGREGATE_SUBSCRIPTION = "Subscription";
    public static final String AGGREGATE_INVOICE = "Invoice";
    public static final String AGGREGATE_FRAUD_ASSESSMENT = "FraudAssessment";
    public static final String AGGREGATE_FRAUD_RULE = "FraudRule";
    public static final String AGGREGATE_FX_RATE = "FxRate";
    public static final String AGGREGATE_FX_LOCK = "FxLock";
    public static final String AGGREGATE_FX_CONVERSION = "FxConversion";
    public static final String AGGREGATE_ROUTING_DECISION = "RoutingDecision";
    public static final String AGGREGATE_CONNECTED_ACCOUNT = "ConnectedAccount";
    public static final String AGGREGATE_SPLIT_PAYMENT = "SplitPayment";
    public static final String AGGREGATE_PAYOUT = "Payout";
}
