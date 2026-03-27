package io.nexuspay.marketplace.domain;

/**
 * Lifecycle states for a connected account.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public enum AccountState {
    ONBOARDING,
    VERIFIED,
    ACTIVE,
    SUSPENDED,
    CLOSED
}
