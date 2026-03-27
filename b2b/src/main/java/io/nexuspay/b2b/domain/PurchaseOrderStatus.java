package io.nexuspay.b2b.domain;

/**
 * Lifecycle states for a purchase order.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public enum PurchaseOrderStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    INVOICED,
    PAID,
    CANCELLED
}
