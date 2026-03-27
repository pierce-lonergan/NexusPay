package io.nexuspay.b2b.domain;

/**
 * Lifecycle states for a vendor payment.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public enum VendorPaymentStatus {
    PENDING,
    APPROVED,
    PROCESSING,
    PAID,
    FAILED
}
