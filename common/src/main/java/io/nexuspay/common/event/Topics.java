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

    /** Fraud assessment events. Partition key: payment_id. */
    public static final String FRAUD_ASSESSMENTS = "nexuspay.fraud.assessments";

    /** Fraud detection events (checks, rule triggers). Partition key: assessment_id. */
    public static final String FRAUD_EVENTS = "nexuspay.fraud.events";

    /** Fraud rule changelog events. Partition key: rule_id. */
    public static final String FRAUD_RULES_CHANGELOG = "nexuspay.fraud.rules.changelog";

    /** Dead letter topic for fraud events. */
    public static final String FRAUD_DLT = "nexuspay.fraud.DLT";

    /** FX rate update events. Partition key: currencyPair. */
    public static final String FX_RATES = "nexuspay.fx.rates";

    /** Currency conversion events. Partition key: tenantId:paymentId. */
    public static final String FX_CONVERSIONS = "nexuspay.fx.conversions";

    /** FX rate lock events. Partition key: tenantId:lockId. */
    public static final String FX_LOCKS = "nexuspay.fx.locks";

    /** Dead letter topic for FX events. */
    public static final String FX_DLT = "nexuspay.fx.DLT";

    /** Routing decision events. Partition key: payment_id. */
    public static final String ROUTING_DECISIONS = "nexuspay.routing.decisions";

    /** Cascade failover events. Partition key: payment_id. */
    public static final String ROUTING_CASCADES = "nexuspay.routing.cascades";

    /** Routing failure events. Partition key: payment_id. */
    public static final String ROUTING_FAILURES = "nexuspay.routing.failures";

    /** Dead letter topic for routing events. */
    public static final String ROUTING_DLT = "nexuspay.routing.DLT";

    // Consumer group IDs
    public static final String LEDGER_CONSUMER_GROUP = "nexuspay-ledger-consumer";
    public static final String GATEWAY_CONSUMER_GROUP = "nexuspay-gateway-consumer";
    public static final String BILLING_CONSUMER_GROUP = "nexuspay-billing-consumer";
    public static final String FRAUD_CONSUMER_GROUP = "nexuspay-fraud-consumer";
    public static final String FX_CONSUMER_GROUP = "nexuspay-fx-consumer";
    public static final String ROUTING_CONSUMER_GROUP = "nexuspay-routing-consumer";
    public static final String DLQ_CONSUMER_GROUP = "nexuspay-dlq-consumer";
}
