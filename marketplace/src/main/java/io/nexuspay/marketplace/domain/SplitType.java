package io.nexuspay.marketplace.domain;

/**
 * How a split rule divides payment proceeds.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public enum SplitType {
    /** Fixed percentage of the payment total. */
    PERCENTAGE,
    /** Absolute fixed amount in minor currency units. */
    FIXED,
    /** Receives whatever remains after other rules are applied. */
    REMAINDER
}
