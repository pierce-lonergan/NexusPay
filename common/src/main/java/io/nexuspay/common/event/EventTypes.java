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

    // B2B lifecycle (Sprint 4.3)
    public static final String PURCHASE_ORDER_CREATED = "PurchaseOrderCreated";
    public static final String PURCHASE_ORDER_SUBMITTED = "PurchaseOrderSubmitted";
    public static final String PURCHASE_ORDER_APPROVED = "PurchaseOrderApproved";
    public static final String PURCHASE_ORDER_CANCELLED = "PurchaseOrderCancelled";
    public static final String INVOICE_CREATED_FROM_PO = "InvoiceCreatedFromPO";
    public static final String B2B_INVOICE_SENT = "InvoiceSent";
    public static final String B2B_INVOICE_PAID = "InvoicePaid";
    public static final String VIRTUAL_CARD_ISSUED = "VirtualCardIssued";
    public static final String VIRTUAL_CARD_FROZEN = "VirtualCardFrozen";
    public static final String VIRTUAL_CARD_CANCELLED = "VirtualCardCancelled";
    public static final String VENDOR_PAYMENT_CREATED = "VendorPaymentCreated";
    public static final String VENDOR_PAYMENT_APPROVED = "VendorPaymentApproved";
    public static final String VENDOR_PAYMENT_BATCH_CREATED = "VendorPaymentBatchCreated";
    // GAP-068 (b2b maker-checker) lifecycle. INTERNAL-only, like every other b2b event above — none of
    // the b2b events are in WebhookEventTaxonomy.CANONICAL, so no webhook/SDK-parity drift guard changes.
    public static final String VENDOR_PAYMENT_APPROVAL_REQUESTED = "VendorPaymentApprovalRequested";
    public static final String VENDOR_PAYMENT_APPROVAL_REJECTED = "VendorPaymentApprovalRejected";
    public static final String VENDOR_PAYMENT_PAID = "VendorPaymentPaid";
    public static final String PURCHASE_ORDER_APPROVAL_REQUESTED = "PurchaseOrderApprovalRequested";
    public static final String PURCHASE_ORDER_APPROVAL_REJECTED = "PurchaseOrderApprovalRejected";

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

    // Dispute / chargeback lifecycle (TEST-2). These are the internal, PascalCase outbound dispute
    // event types translated to dotted canonical names by WebhookEventTaxonomy at webhook SEND time.
    // The dispute domain emits them through the transactional outbox (DisputeOutboxAdapter) so a
    // merchant subscribed to a dispute.* event (or "*") is notified on every chargeback transition —
    // closing the silent over-grant where the money was pulled back but no event ever fired.
    public static final String DISPUTE_CREATED = "DisputeCreated";
    public static final String DISPUTE_FUNDS_WITHDRAWN = "DisputeFundsWithdrawn";
    public static final String DISPUTE_EVIDENCE_NEEDED = "DisputeEvidenceNeeded";
    public static final String DISPUTE_EVIDENCE_SUBMITTED = "DisputeEvidenceSubmitted";
    public static final String DISPUTE_WON = "DisputeWon";
    public static final String DISPUTE_LOST = "DisputeLost";
    public static final String DISPUTE_CLOSED = "DisputeClosed";

    // Workflow builder lifecycle (Sprint 4.4)
    public static final String WORKFLOW_CREATED = "WorkflowCreated";
    public static final String WORKFLOW_PUBLISHED = "WorkflowPublished";
    public static final String WORKFLOW_ARCHIVED = "WorkflowArchived";
    public static final String WORKFLOW_ROLLED_BACK = "WorkflowRolledBack";
    public static final String WORKFLOW_EXECUTION_STARTED = "WorkflowExecutionStarted";
    public static final String WORKFLOW_EXECUTION_COMPLETED = "WorkflowExecutionCompleted";
    public static final String WORKFLOW_EXECUTION_FAILED = "WorkflowExecutionFailed";
    public static final String WORKFLOW_EXECUTION_CANCELLED = "WorkflowExecutionCancelled";
    public static final String WEBHOOK_TRIGGER_CREATED = "WebhookTriggerCreated";

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
    public static final String AGGREGATE_PURCHASE_ORDER = "PurchaseOrder";
    public static final String AGGREGATE_B2B_INVOICE = "B2bInvoice";
    public static final String AGGREGATE_VIRTUAL_CARD = "VirtualCard";
    public static final String AGGREGATE_VENDOR_PAYMENT = "VendorPayment";
    public static final String AGGREGATE_WORKFLOW_DEFINITION = "WorkflowDefinition";
    public static final String AGGREGATE_WORKFLOW_EXECUTION = "WorkflowExecution";
    public static final String AGGREGATE_WEBHOOK_TRIGGER = "WebhookTrigger";

    // TEST-2: dispute aggregate. The outbox relay has no explicit Topics mapping for "Dispute", so it
    // falls through to the DEFAULT_TOPIC (Topics.PAYMENTS) — the SAME topic WebhookDeliveryService
    // consumes — so a dispute outbox row reaches the canonical signed-delivery pipeline with no relay
    // change. Matches the aggregate_type the DisputeOutboxAdapter native INSERT writes.
    public static final String AGGREGATE_DISPUTE = "Dispute";
}
