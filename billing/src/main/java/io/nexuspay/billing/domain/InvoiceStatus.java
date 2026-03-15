package io.nexuspay.billing.domain;

/**
 * Invoice lifecycle states.
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
public enum InvoiceStatus {

    /** Invoice created but not yet finalised. */
    DRAFT,

    /** Invoice finalised and awaiting payment. */
    OPEN,

    /** Payment collected successfully. */
    PAID,

    /** Invoice voided (e.g., billing error correction). */
    VOID,

    /** Payment collection exhausted — written off. */
    UNCOLLECTIBLE
}
