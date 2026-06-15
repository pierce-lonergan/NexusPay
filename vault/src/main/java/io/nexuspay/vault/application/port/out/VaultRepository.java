package io.nexuspay.vault.application.port.out;

import io.nexuspay.vault.domain.NetworkToken;
import io.nexuspay.vault.domain.VaultMigration;
import io.nexuspay.vault.domain.VaultToken;
import io.nexuspay.vault.domain.VaultedCard;

import java.util.List;
import java.util.Optional;

/**
 * Out-port for vault persistence operations.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public interface VaultRepository {

    // VaultedCard
    VaultedCard saveCard(VaultedCard card);
    Optional<VaultedCard> findCardById(String id);
    Optional<VaultedCard> findCardByFingerprint(String tenantId, String fingerprint);
    List<VaultedCard> findCardsByEncryptionKeyId(String keyId);
    void deleteCard(String id);

    // VaultToken
    VaultToken saveToken(VaultToken token);
    Optional<VaultToken> findTokenById(String tokenId);
    /** SEC-BATCH-1: tenant-scoped by-id lookup — empty when absent OR owned by another tenant. */
    Optional<VaultToken> findTokenById(String tokenId, String tenantId);
    Optional<VaultToken> findTokenByVaultedCardId(String vaultedCardId);
    void deleteToken(String tokenId);

    // NetworkToken
    NetworkToken saveNetworkToken(NetworkToken token);
    Optional<NetworkToken> findNetworkTokenById(String id);
    /** SEC-BATCH-1: tenant-scoped by-id lookup. */
    Optional<NetworkToken> findNetworkTokenById(String id, String tenantId);
    List<NetworkToken> findNetworkTokensByCardId(String cardId);
    void deleteNetworkTokensByCardId(String cardId);

    // VaultMigration
    VaultMigration saveMigration(VaultMigration migration);
    Optional<VaultMigration> findMigrationById(String id);
    /** SEC-BATCH-1: tenant-scoped by-id lookup. */
    Optional<VaultMigration> findMigrationById(String id, String tenantId);
}
