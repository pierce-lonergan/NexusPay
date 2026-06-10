package io.nexuspay.vault.application.service;

import io.nexuspay.vault.application.port.in.VaultCardUseCase;
import io.nexuspay.vault.application.port.out.EncryptionPort;
import io.nexuspay.vault.application.port.out.VaultEventPublisher;
import io.nexuspay.vault.application.port.out.VaultRepository;
import io.nexuspay.vault.domain.CardBrand;
import io.nexuspay.vault.domain.VaultToken;
import io.nexuspay.vault.domain.VaultedCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Core service for vaulting, retrieving, and deleting cards.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
@Service
public class CardVaultService implements VaultCardUseCase {

    private static final Logger log = LoggerFactory.getLogger(CardVaultService.class);

    private final VaultRepository repository;
    private final EncryptionPort encryption;
    private final VaultEventPublisher eventPublisher;

    public CardVaultService(VaultRepository repository, EncryptionPort encryption,
                            VaultEventPublisher eventPublisher) {
        this.repository = repository;
        this.encryption = encryption;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public VaultCardResult vaultCard(VaultCardCommand command) {
        String pan = command.pan().trim();

        if (!luhnCheck(pan)) {
            throw new IllegalArgumentException("Invalid card number: Luhn check failed");
        }

        // Generate fingerprint for dedup
        String fingerprint = encryption.generateFingerprint(pan);

        // Check for duplicate — return the existing token instead of inserting a
        // second row (which would violate the (tenant_id, fingerprint) unique
        // constraint and surface as a 500).
        var existing = repository.findCardByFingerprint(command.tenantId(), fingerprint);
        if (existing.isPresent()) {
            log.info("Duplicate card detected for tenant={}, returning existing vault token", command.tenantId());
            var existingCard = existing.get();
            var existingToken = repository.findTokenByVaultedCardId(existingCard.getId());
            if (existingToken.isPresent()) {
                return new VaultCardResult(
                        existingToken.get().getId(),
                        existingCard.getPanLast4(),
                        existingCard.getBrand(),
                        fingerprint
                );
            }
        }

        // Extract card details. Store at most the first 6 PAN digits (BIN): with
        // first-6 + last-4 known, only the middle digits remain secret, so a
        // wider stored prefix needlessly shrinks the unknown space (PCI guidance).
        String panLast4 = pan.substring(pan.length() - 4);
        String panBin = pan.substring(0, Math.min(pan.length(), 6));
        CardBrand brand = detectBrand(panBin);

        // Encrypt PAN
        String keyId = encryption.currentKeyId();
        EncryptionPort.EncryptionResult encrypted = encryption.encrypt(
                pan.getBytes(StandardCharsets.UTF_8), keyId);

        // Create and persist domain objects
        VaultedCard card = VaultedCard.create(
                command.tenantId(), encrypted.ciphertext(), panLast4, panBin,
                brand, command.expMonth(), command.expYear(),
                command.cardholderName(), encrypted.keyId(), fingerprint
        );
        card = repository.saveCard(card);

        VaultToken token = VaultToken.create(command.tenantId(), card.getId());
        token = repository.saveToken(token);

        // Publish event
        eventPublisher.publishEvent("VaultedCard", card.getId(), "CardVaulted",
                Map.of("vaultTokenId", token.getId(), "brand", brand.name(),
                        "panLast4", panLast4, "tenantId", command.tenantId()),
                command.tenantId());

        log.info("Card vaulted: token={}, brand={}, tenant={}", token.getId(), brand, command.tenantId());

        return new VaultCardResult(token.getId(), panLast4, brand, fingerprint);
    }

    @Override
    @Transactional(readOnly = true)
    public VaultedCardInfo getCard(String vaultTokenId, String tenantId) {
        VaultToken token = repository.findTokenById(vaultTokenId)
                .orElseThrow(() -> new IllegalArgumentException("Vault token not found: " + vaultTokenId));

        VaultedCard card = repository.findCardById(token.getVaultedCardId())
                .orElseThrow(() -> new IllegalStateException("Vaulted card not found for token: " + vaultTokenId));

        return new VaultedCardInfo(
                vaultTokenId, card.getPanLast4(), card.getPanBin(), card.getBrand(),
                card.getExpMonth(), card.getExpYear(), card.getCardholderName(),
                card.getCreatedAt()
        );
    }

    @Override
    @Transactional
    public void deleteCard(String vaultTokenId, String tenantId) {
        VaultToken token = repository.findTokenById(vaultTokenId)
                .orElseThrow(() -> new IllegalArgumentException("Vault token not found: " + vaultTokenId));

        String cardId = token.getVaultedCardId();

        // Cascade delete: network tokens → vault token → vaulted card
        repository.deleteNetworkTokensByCardId(cardId);
        repository.deleteToken(vaultTokenId);
        repository.deleteCard(cardId);

        eventPublisher.publishEvent("VaultedCard", cardId, "CardDeleted",
                Map.of("vaultTokenId", vaultTokenId, "tenantId", tenantId),
                tenantId);

        log.info("Card deleted: token={}, tenant={}", vaultTokenId, tenantId);
    }

    static CardBrand detectBrand(String panBin) {
        if (panBin == null || panBin.isEmpty()) {
            throw new IllegalArgumentException("PAN BIN cannot be empty");
        }
        char first = panBin.charAt(0);
        if (first == '4') {
            return CardBrand.VISA;
        }
        if (first == '5' || (panBin.length() >= 2 && panBin.startsWith("2"))) {
            // Mastercard: 51-55 and 2221-2720 ranges
            if (first == '5') {
                int second = Character.getNumericValue(panBin.charAt(1));
                if (second >= 1 && second <= 5) return CardBrand.MASTERCARD;
            }
            if (panBin.length() >= 4) {
                int prefix4 = Integer.parseInt(panBin.substring(0, 4));
                if (prefix4 >= 2221 && prefix4 <= 2720) return CardBrand.MASTERCARD;
            }
        }
        if (first == '3') {
            if (panBin.length() >= 2) {
                char second = panBin.charAt(1);
                if (second == '4' || second == '7') return CardBrand.AMEX;
            }
        }
        if (first == '6') {
            return CardBrand.DISCOVER;
        }
        return CardBrand.VISA; // Default fallback
    }

    static boolean luhnCheck(String number) {
        int sum = 0;
        boolean alternate = false;
        for (int i = number.length() - 1; i >= 0; i--) {
            char c = number.charAt(i);
            if (!Character.isDigit(c)) continue;
            int n = Character.getNumericValue(c);
            if (alternate) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            alternate = !alternate;
        }
        return sum > 0 && sum % 10 == 0;
    }

}
