package io.nexuspay.vault.application.port.in;

import io.nexuspay.vault.domain.VaultMigration;

/**
 * Use case for vault-to-vault migration from external providers.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public interface MigrateVaultUseCase {

    VaultMigration startMigration(String tenantId, String sourceProvider, int totalCards);

    VaultMigration getMigrationStatus(String migrationId, String tenantId);
}
