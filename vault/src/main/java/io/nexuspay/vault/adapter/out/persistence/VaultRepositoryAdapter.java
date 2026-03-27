package io.nexuspay.vault.adapter.out.persistence;

import io.nexuspay.vault.application.port.out.VaultRepository;
import io.nexuspay.vault.domain.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Adapter implementing {@link VaultRepository} by delegating to Spring Data JPA
 * repositories and mapping between domain models and JPA entities.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
@Component
public class VaultRepositoryAdapter implements VaultRepository {

    private final JpaVaultedCardRepository cardRepo;
    private final JpaVaultTokenRepository tokenRepo;
    private final JpaNetworkTokenRepository networkTokenRepo;
    private final JpaVaultMigrationRepository migrationRepo;

    public VaultRepositoryAdapter(JpaVaultedCardRepository cardRepo,
                                  JpaVaultTokenRepository tokenRepo,
                                  JpaNetworkTokenRepository networkTokenRepo,
                                  JpaVaultMigrationRepository migrationRepo) {
        this.cardRepo = cardRepo;
        this.tokenRepo = tokenRepo;
        this.networkTokenRepo = networkTokenRepo;
        this.migrationRepo = migrationRepo;
    }

    // --- VaultedCard ---

    @Override
    public VaultedCard saveCard(VaultedCard card) {
        return toDomain(cardRepo.save(toEntity(card)));
    }

    @Override
    public Optional<VaultedCard> findCardById(String id) {
        return cardRepo.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<VaultedCard> findCardByFingerprint(String tenantId, String fingerprint) {
        return cardRepo.findByTenantIdAndFingerprint(tenantId, fingerprint).map(this::toDomain);
    }

    @Override
    public List<VaultedCard> findCardsByEncryptionKeyId(String keyId) {
        return cardRepo.findByEncryptionKeyId(keyId).stream().map(this::toDomain).toList();
    }

    @Override
    public void deleteCard(String id) {
        cardRepo.deleteById(id);
    }

    // --- VaultToken ---

    @Override
    public VaultToken saveToken(VaultToken token) {
        return toDomain(tokenRepo.save(toEntity(token)));
    }

    @Override
    public Optional<VaultToken> findTokenById(String tokenId) {
        return tokenRepo.findById(tokenId).map(this::toDomain);
    }

    @Override
    public void deleteToken(String tokenId) {
        tokenRepo.deleteById(tokenId);
    }

    // --- NetworkToken ---

    @Override
    public NetworkToken saveNetworkToken(NetworkToken token) {
        return toDomain(networkTokenRepo.save(toEntity(token)));
    }

    @Override
    public Optional<NetworkToken> findNetworkTokenById(String id) {
        return networkTokenRepo.findById(id).map(this::toDomain);
    }

    @Override
    public List<NetworkToken> findNetworkTokensByCardId(String cardId) {
        return networkTokenRepo.findByVaultedCardId(cardId).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public void deleteNetworkTokensByCardId(String cardId) {
        networkTokenRepo.deleteByVaultedCardId(cardId);
    }

    // --- VaultMigration ---

    @Override
    public VaultMigration saveMigration(VaultMigration migration) {
        return toDomain(migrationRepo.save(toEntity(migration)));
    }

    @Override
    public Optional<VaultMigration> findMigrationById(String id) {
        return migrationRepo.findById(id).map(this::toDomain);
    }

    // --- Entity ↔ Domain Mapping: VaultedCard ---

    private VaultedCardEntity toEntity(VaultedCard card) {
        VaultedCardEntity e = new VaultedCardEntity();
        e.setId(card.getId());
        e.setTenantId(card.getTenantId());
        e.setEncryptedPan(card.getEncryptedPan());
        e.setPanLast4(card.getPanLast4());
        e.setPanBin(card.getPanBin());
        e.setBrand(card.getBrand().name());
        e.setExpMonth((short) card.getExpMonth());
        e.setExpYear((short) card.getExpYear());
        e.setCardholderName(card.getCardholderName());
        e.setEncryptionKeyId(card.getEncryptionKeyId());
        e.setFingerprint(card.getFingerprint());
        e.setCreatedAt(card.getCreatedAt());
        return e;
    }

    private VaultedCard toDomain(VaultedCardEntity e) {
        VaultedCard card = new VaultedCard();
        card.setId(e.getId());
        card.setTenantId(e.getTenantId());
        card.setEncryptedPan(e.getEncryptedPan());
        card.setPanLast4(e.getPanLast4());
        card.setPanBin(e.getPanBin());
        card.setBrand(CardBrand.valueOf(e.getBrand()));
        card.setExpMonth(e.getExpMonth());
        card.setExpYear(e.getExpYear());
        card.setCardholderName(e.getCardholderName());
        card.setEncryptionKeyId(e.getEncryptionKeyId());
        card.setFingerprint(e.getFingerprint());
        card.setCreatedAt(e.getCreatedAt());
        return card;
    }

    // --- Entity ↔ Domain Mapping: VaultToken ---

    private VaultTokenEntity toEntity(VaultToken token) {
        VaultTokenEntity e = new VaultTokenEntity();
        e.setId(token.getId());
        e.setVaultedCardId(token.getVaultedCardId());
        e.setTenantId(token.getTenantId());
        e.setCreatedAt(token.getCreatedAt());
        return e;
    }

    private VaultToken toDomain(VaultTokenEntity e) {
        VaultToken token = new VaultToken();
        token.setId(e.getId());
        token.setVaultedCardId(e.getVaultedCardId());
        token.setTenantId(e.getTenantId());
        token.setCreatedAt(e.getCreatedAt());
        return token;
    }

    // --- Entity ↔ Domain Mapping: NetworkToken ---

    private NetworkTokenEntity toEntity(NetworkToken nt) {
        NetworkTokenEntity e = new NetworkTokenEntity();
        e.setId(nt.getId());
        e.setVaultedCardId(nt.getVaultedCardId());
        e.setTenantId(nt.getTenantId());
        e.setNetwork(nt.getNetwork().name());
        e.setTokenReference(nt.getTokenReference());
        e.setTokenLast4(nt.getTokenLast4());
        e.setStatus(nt.getStatus().name());
        e.setTokenExpiry(nt.getTokenExpiry());
        e.setProvisionedAt(nt.getProvisionedAt());
        e.setLastUsedAt(nt.getLastUsedAt());
        e.setSuspendedAt(nt.getSuspendedAt());
        return e;
    }

    private NetworkToken toDomain(NetworkTokenEntity e) {
        NetworkToken nt = new NetworkToken();
        nt.setId(e.getId());
        nt.setVaultedCardId(e.getVaultedCardId());
        nt.setTenantId(e.getTenantId());
        nt.setNetwork(NetworkType.valueOf(e.getNetwork()));
        nt.setTokenReference(e.getTokenReference());
        nt.setTokenLast4(e.getTokenLast4());
        nt.setStatus(TokenState.valueOf(e.getStatus()));
        nt.setTokenExpiry(e.getTokenExpiry());
        nt.setProvisionedAt(e.getProvisionedAt());
        nt.setLastUsedAt(e.getLastUsedAt());
        nt.setSuspendedAt(e.getSuspendedAt());
        return nt;
    }

    // --- Entity ↔ Domain Mapping: VaultMigration ---

    private VaultMigrationEntity toEntity(VaultMigration m) {
        VaultMigrationEntity e = new VaultMigrationEntity();
        e.setId(m.getId());
        e.setTenantId(m.getTenantId());
        e.setSourceProvider(m.getSourceProvider());
        e.setStatus(m.getStatus().name());
        e.setTotalCards(m.getTotalCards());
        e.setMigratedCount(m.getMigratedCount());
        e.setFailedCount(m.getFailedCount());
        e.setStartedAt(m.getStartedAt());
        e.setCompletedAt(m.getCompletedAt());
        e.setCreatedAt(m.getCreatedAt());
        return e;
    }

    private VaultMigration toDomain(VaultMigrationEntity e) {
        VaultMigration m = new VaultMigration();
        m.setId(e.getId());
        m.setTenantId(e.getTenantId());
        m.setSourceProvider(e.getSourceProvider());
        m.setStatus(MigrationStatus.valueOf(e.getStatus()));
        m.setTotalCards(e.getTotalCards());
        m.setMigratedCount(e.getMigratedCount());
        m.setFailedCount(e.getFailedCount());
        m.setStartedAt(e.getStartedAt());
        m.setCompletedAt(e.getCompletedAt());
        m.setCreatedAt(e.getCreatedAt());
        return m;
    }
}
