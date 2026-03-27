package io.nexuspay.b2b.domain;

/**
 * Level 2 commercial card data for reduced interchange rates.
 * Contains summary-level fields required by Visa/MC for corporate card transactions.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public record Level2Data(
        String customerReferenceNumber,
        long taxAmount,
        String taxIndicator,
        String merchantTaxId,
        String purchaseOrderNumber
) {}
