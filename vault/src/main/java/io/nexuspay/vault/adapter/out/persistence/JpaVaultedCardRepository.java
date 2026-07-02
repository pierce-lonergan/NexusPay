package io.nexuspay.vault.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link VaultedCardEntity}.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public interface JpaVaultedCardRepository extends JpaRepository<VaultedCardEntity, String> {

    Optional<VaultedCardEntity> findByTenantIdAndFingerprint(String tenantId, String fingerprint);

    List<VaultedCardEntity> findByEncryptionKeyId(String encryptionKeyId);

    /**
     * GAP-059: page-bounded variant for the key-rotation job. The adapter passes
     * {@code PageRequest.of(0, batchSize)} so at most one batch of cards on the retired key is
     * loaded at a time.
     */
    List<VaultedCardEntity> findByEncryptionKeyId(String encryptionKeyId, Pageable pageable);
}
