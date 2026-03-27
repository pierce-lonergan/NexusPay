package io.nexuspay.b2b.domain;

/**
 * Payment methods for vendor disbursements.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public enum VendorPaymentMethod {
    ACH,
    WIRE,
    VIRTUAL_CARD,
    CHECK
}
