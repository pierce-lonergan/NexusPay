package io.nexuspay.vault.domain;

/**
 * Lifecycle states for a network token.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public enum TokenState {
    PROVISIONED,
    ACTIVE,
    SUSPENDED,
    DELETED
}
