package io.nexuspay.dispute.domain;

/**
 * Types of evidence that can be attached to a dispute.
 *
 * <p>Card networks accept specific categories of evidence during
 * representment.  This enum enumerates the categories NexusPay supports.</p>
 *
 * @since 0.2.4 (Sprint 2.4)
 */
public enum DisputeEvidenceType {

    /** Proof of shipment / delivery confirmation. */
    SHIPPING_RECEIPT,

    /** Customer communication records (emails, chat logs). */
    CUSTOMER_COMMUNICATION,

    /** IP address / device fingerprint logs proving cardholder presence. */
    IP_LOG,

    /** Receipt or invoice for the transaction. */
    RECEIPT,

    /** Copy of the refund/cancellation policy shown to customer. */
    CANCELLATION_POLICY,

    /** Signed contract or terms of service. */
    SERVICE_AGREEMENT,

    /** Screenshot or media showing product/service was delivered as described. */
    PRODUCT_DESCRIPTION,

    /** Any other supporting document. */
    OTHER
}
