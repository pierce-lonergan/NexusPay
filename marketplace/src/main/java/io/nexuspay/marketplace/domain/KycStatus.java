package io.nexuspay.marketplace.domain;

/**
 * KYC/KYB verification status for connected accounts.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public enum KycStatus {
    PENDING,
    IN_REVIEW,
    VERIFIED,
    FAILED,
    EXPIRED
}
