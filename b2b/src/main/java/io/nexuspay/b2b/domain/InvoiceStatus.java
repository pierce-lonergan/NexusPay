package io.nexuspay.b2b.domain;

/**
 * Lifecycle states for a B2B invoice.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public enum InvoiceStatus {
    DRAFT,
    SENT,
    PAID,
    OVERDUE,
    CANCELLED
}
