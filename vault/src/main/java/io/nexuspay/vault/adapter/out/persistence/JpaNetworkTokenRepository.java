package io.nexuspay.vault.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link NetworkTokenEntity}.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public interface JpaNetworkTokenRepository extends JpaRepository<NetworkTokenEntity, String> {

    List<NetworkTokenEntity> findByVaultedCardId(String vaultedCardId);

    void deleteByVaultedCardId(String vaultedCardId);
}
