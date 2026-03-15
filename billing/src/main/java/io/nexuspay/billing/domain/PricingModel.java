package io.nexuspay.billing.domain;

/**
 * Pricing strategies supported by the billing engine.
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
public enum PricingModel {

    /** Fixed amount per billing interval (e.g., $49/month). */
    FLAT,

    /** Fixed amount multiplied by quantity (e.g., $10/seat/month). */
    PER_UNIT,

    /** Graduated tiers — each tier priced independently (first 10 at $5, next 10 at $3, etc.). */
    TIERED,

    /** Volume-based — single tier applies to total quantity (50 units → all at $3/unit). */
    VOLUME,

    /** Package pricing — charge per block of units (e.g., $100 per 100 API calls). */
    PACKAGE
}
