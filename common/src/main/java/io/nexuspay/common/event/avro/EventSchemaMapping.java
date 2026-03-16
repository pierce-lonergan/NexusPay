package io.nexuspay.common.event.avro;

import io.nexuspay.common.event.EventTypes;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificData;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps event_type strings to their corresponding Avro {@link Schema}.
 * Schemas are loaded from the Avro-generated classes produced by the avro-gradle-plugin.
 *
 * <p>Thread-safe: schemas are loaded lazily and cached in a {@link ConcurrentHashMap}.
 */
public final class EventSchemaMapping {

    private EventSchemaMapping() {}

    /**
     * Fully qualified Avro class names indexed by event type constant.
     * The avro-gradle-plugin generates these classes from .avsc files at build time.
     */
    private static final Map<String, String> EVENT_TYPE_TO_CLASS = Map.ofEntries(
            // Payment
            Map.entry(EventTypes.PAYMENT_CREATED, "io.nexuspay.avro.payment.PaymentCreated"),
            Map.entry(EventTypes.PAYMENT_AUTHORIZED, "io.nexuspay.avro.payment.PaymentAuthorized"),
            Map.entry(EventTypes.PAYMENT_CAPTURED, "io.nexuspay.avro.payment.PaymentCaptured"),
            Map.entry(EventTypes.PAYMENT_VOIDED, "io.nexuspay.avro.payment.PaymentVoided"),
            Map.entry(EventTypes.PAYMENT_FAILED, "io.nexuspay.avro.payment.PaymentFailed"),
            Map.entry(EventTypes.REFUND_CREATED, "io.nexuspay.avro.payment.RefundCreated"),
            Map.entry(EventTypes.REFUND_COMPLETED, "io.nexuspay.avro.payment.RefundCompleted"),
            Map.entry(EventTypes.REFUND_FAILED, "io.nexuspay.avro.payment.RefundFailed"),
            // Ledger
            Map.entry(EventTypes.LEDGER_ENTRY_CREATED, "io.nexuspay.avro.ledger.LedgerEntryCreated"),
            Map.entry("BalanceUpdated", "io.nexuspay.avro.ledger.BalanceUpdated"),
            // Billing
            Map.entry(EventTypes.SUBSCRIPTION_CREATED, "io.nexuspay.avro.billing.SubscriptionCreated"),
            Map.entry("InvoiceGenerated", "io.nexuspay.avro.billing.InvoiceGenerated"),
            Map.entry("DunningAttempted", "io.nexuspay.avro.billing.DunningAttempted"),
            // Fraud
            Map.entry(EventTypes.FRAUD_CHECK_PASSED, "io.nexuspay.avro.fraud.FraudCheckPassed"),
            Map.entry(EventTypes.FRAUD_CHECK_FAILED, "io.nexuspay.avro.fraud.FraudCheckFailed"),
            Map.entry(EventTypes.FRAUD_CHECK_REVIEW, "io.nexuspay.avro.fraud.FraudCheckReview"),
            Map.entry(EventTypes.FRAUD_RULE_TRIGGERED, "io.nexuspay.avro.fraud.RuleTriggered"),
            // Routing
            Map.entry(EventTypes.ROUTE_SELECTED, "io.nexuspay.avro.routing.RouteSelected"),
            Map.entry(EventTypes.ROUTE_FAILED, "io.nexuspay.avro.routing.RouteFailed"),
            Map.entry(EventTypes.CASCADE_TRIGGERED, "io.nexuspay.avro.routing.CascadeTriggered")
    );

    /** Lazily cached schemas (class name -> Schema). */
    private static final ConcurrentHashMap<String, Schema> SCHEMA_CACHE = new ConcurrentHashMap<>();

    /**
     * Returns the Avro {@link Schema} for the given event type, if a mapping exists.
     *
     * @param eventType the event_type string (e.g. "PaymentCreated")
     * @return the schema, or empty if the event type has no Avro mapping
     */
    public static Optional<Schema> schemaFor(String eventType) {
        String className = EVENT_TYPE_TO_CLASS.get(eventType);
        if (className == null) {
            return Optional.empty();
        }
        return Optional.of(SCHEMA_CACHE.computeIfAbsent(className, EventSchemaMapping::loadSchema));
    }

    /**
     * Returns all registered event type names.
     */
    public static java.util.Set<String> registeredEventTypes() {
        return EVENT_TYPE_TO_CLASS.keySet();
    }

    /**
     * Checks whether an Avro schema mapping exists for the given event type.
     */
    public static boolean hasSchema(String eventType) {
        return EVENT_TYPE_TO_CLASS.containsKey(eventType);
    }

    private static Schema loadSchema(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return (Schema) clazz.getMethod("getClassSchema").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Failed to load Avro schema for class: " + className +
                    ". Ensure the avro-gradle-plugin has generated the class.", e);
        }
    }
}
