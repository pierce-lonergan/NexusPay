package io.nexuspay.vault.domain;

/**
 * Lifecycle states for a vault-to-vault migration.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public enum MigrationStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
