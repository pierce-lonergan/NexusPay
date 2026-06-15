package io.nexuspay.vault.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link VaultTokenEntity}.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public interface JpaVaultTokenRepository extends JpaRepository<VaultTokenEntity, String> {

    Optional<VaultTokenEntity> findByVaultedCardId(String vaultedCardId);

    // SEC-BATCH-1: tenant-scoped by-id lookup (cardholder-data access must be tenant-isolated in SQL).
    Optional<VaultTokenEntity> findByIdAndTenantId(String id, String tenantId);
}
