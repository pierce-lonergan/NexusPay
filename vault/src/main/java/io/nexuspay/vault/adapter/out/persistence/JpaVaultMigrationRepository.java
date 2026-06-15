package io.nexuspay.vault.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link VaultMigrationEntity}.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public interface JpaVaultMigrationRepository extends JpaRepository<VaultMigrationEntity, String> {

    // SEC-BATCH-1: tenant-scoped by-id lookup.
    Optional<VaultMigrationEntity> findByIdAndTenantId(String id, String tenantId);
}
