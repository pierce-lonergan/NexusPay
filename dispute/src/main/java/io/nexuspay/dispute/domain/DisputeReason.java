package io.nexuspay.dispute.domain;

/**
 * Standardised dispute reason codes mapped from card-network-specific codes.
 *
 * <p>Each network (Visa, Mastercard, Amex) publishes its own reason taxonomy.
 * This enum normalises them into a domain-level set so business logic and
 * auto-representment rules can operate network-agnostically.</p>
 *
 * @since 0.2.4 (Sprint 2.4)
 */
public enum DisputeReason {

    /** Cardholder claims the transaction was not authorised. */
    FRAUDULENT,

    /** Cardholder does not recognise the charge. */
    UNRECOGNIZED,

    /** Product/service not received. */
    PRODUCT_NOT_RECEIVED,

    /** Product/service significantly different from description. */
    PRODUCT_NOT_AS_DESCRIBED,

    /** Duplicate or incorrect charge amount. */
    DUPLICATE_CHARGE,

    /** Subscription cancelled but still billed. */
    SUBSCRIPTION_CANCELLED,

    /** Credit/refund not processed after agreement. */
    CREDIT_NOT_PROCESSED,

    /** Catch-all for unmapped or network-specific codes. */
    OTHER;

    /**
     * Best-effort mapping from a raw network reason code string.
     */
    public static DisputeReason fromCode(String code) {
        if (code == null) return OTHER;
        return switch (code.toUpperCase()) {
            case "10.4", "FRAUD", "FRAUDULENT" -> FRAUDULENT;
            case "13.1", "UNRECOGNIZED" -> UNRECOGNIZED;
            case "13.3", "NOT_RECEIVED", "PRODUCT_NOT_RECEIVED" -> PRODUCT_NOT_RECEIVED;
            case "13.4", "NOT_AS_DESCRIBED", "PRODUCT_NOT_AS_DESCRIBED" -> PRODUCT_NOT_AS_DESCRIBED;
            case "12.6", "DUPLICATE", "DUPLICATE_CHARGE" -> DUPLICATE_CHARGE;
            case "13.7", "CANCELLED", "SUBSCRIPTION_CANCELLED" -> SUBSCRIPTION_CANCELLED;
            case "13.6", "CREDIT_NOT_PROCESSED" -> CREDIT_NOT_PROCESSED;
            default -> OTHER;
        };
    }
}
