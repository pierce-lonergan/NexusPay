package io.nexuspay.vault.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link VaultTokenEntity}.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public interface JpaVaultTokenRepository extends JpaRepository<VaultTokenEntity, String> {
}
