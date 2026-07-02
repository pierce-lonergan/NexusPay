package io.nexuspay.vault.application.service;

import io.nexuspay.common.crypto.EncryptionPort;
import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.vault.application.port.in.VaultCardUseCase;
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
import java.util.Arrays;
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
        // SEC-BATCH-1: cardholder-data read scoped to the caller's tenant via the vault TOKEN — 404 on
        // absent OR wrong-tenant (no PAN BIN/last4/name disclosure across tenants, no existence oracle).
        VaultToken token = TenantOwnership.require(
                repository.findTokenById(vaultTokenId, tenantId), "Vault token");

        // Card is reached through the now-tenant-scoped token, so this stays an internal-consistency load.
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
        // SEC-BATCH-1: scope+assert ownership on the vault TOKEN BEFORE the cascade deletes — prevents
        // cross-tenant card destruction. 404 on absent OR wrong-tenant.
        VaultToken token = TenantOwnership.require(
                repository.findTokenById(vaultTokenId, tenantId), "Vault token");

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

    /**
     * GAP-059: outcome of an atomic single-card key rotation.
     */
    public enum RotationOutcome {
        /** The card was re-encrypted from the retired key onto the active key and persisted. */
        ROTATED,
        /** The card was NOT on the retired key (already rotated / on a third key) — no-op, idempotent. */
        SKIPPED
    }

    /**
     * GAP-059: ATOMICALLY re-encrypts one card from {@code retiredKeyId} onto the CURRENT active key.
     *
     * <p><b>Atomicity — a card is never left half-rotated.</b> The whole method runs in ONE
     * {@code @Transactional} unit: decrypt (retired) → encrypt (active) → verify-decrypt-after →
     * single {@code saveCard} that writes BOTH {@code encryptedPan} (new ciphertext) AND
     * {@code encryptionKeyId} (new key id). Those are two columns of the SAME {@code vaulted_cards}
     * row, so one UPDATE commits them together — there is no window where the ciphertext and the
     * stamped key id disagree. Any failure (encrypt, verify, persist, or a crash mid-tx) rolls the
     * whole thing back and the card stays fully valid on the RETIRED key, to be re-selected next
     * cycle.</p>
     *
     * <p><b>Verify-decrypt-after.</b> Before persisting, the freshly-produced ciphertext is decrypted
     * again under the ACTIVE key and byte-compared to the original plaintext; only on an exact match
     * is {@code saveCard} called. This proves the row about to be stamped with the active key id is
     * actually readable under that key — a mismatched card can never commit. (AES-GCM is authenticated
     * so a corrupt ciphertext throws on decrypt anyway; this is cheap belt-and-suspenders.)</p>
     *
     * <p><b>Idempotency.</b> If the card is not currently on {@code retiredKeyId} (already rotated, or
     * on some third key) the method returns {@link RotationOutcome#SKIPPED} WITHOUT decrypting — so a
     * re-run, a racing replica, or an already-current card is a safe no-op.</p>
     *
     * <p><b>No PAN leak.</b> The decrypted PAN lives only in a local {@code byte[]} used immediately
     * for re-encrypt + verify, and is zeroed in a {@code finally}. It is never logged, never put in an
     * exception message, never a metric tag. Logs carry only card id, tenant id, and key ids.</p>
     *
     * @param cardId        the card to rotate
     * @param retiredKeyId  the key id being retired; the card is only touched if it is currently on it
     * @return {@link RotationOutcome#ROTATED} if re-encrypted, {@link RotationOutcome#SKIPPED} if already off the retired key
     * @throws IllegalStateException if the card id is not found
     */
    @Transactional
    public RotationOutcome rotateCardKey(String cardId, String retiredKeyId) {
        VaultedCard card = repository.findCardById(cardId)
                .orElseThrow(() -> new IllegalStateException("Vaulted card not found for rotation: " + cardId));

        // Idempotent guard: only cards CURRENTLY on the retired key are rotated. A card already on the
        // active key (or any other key) is skipped without ever decrypting it.
        if (!retiredKeyId.equals(card.getEncryptionKeyId())) {
            return RotationOutcome.SKIPPED;
        }

        String activeKeyId = encryption.currentKeyId();
        // Defensive: if the "retired" key IS the active key there is nothing to rotate.
        if (activeKeyId.equals(retiredKeyId)) {
            return RotationOutcome.SKIPPED;
        }

        byte[] plaintext = null;
        try {
            // 1. Decrypt under the EXPLICIT retired key id.
            plaintext = encryption.decrypt(card.getEncryptedPan(), retiredKeyId);

            // 2. Re-encrypt under the CURRENT active key.
            EncryptionPort.EncryptionResult reEncrypted = encryption.encrypt(plaintext, activeKeyId);

            // 3. VERIFY-DECRYPT-AFTER: the new ciphertext must decrypt under the active key back to the
            //    original plaintext BEFORE we persist — so a card can never be stamped with a key id its
            //    ciphertext is not actually readable under.
            byte[] roundTrip = encryption.decrypt(reEncrypted.ciphertext(), reEncrypted.keyId());
            try {
                if (!Arrays.equals(plaintext, roundTrip)) {
                    throw new IllegalStateException(
                            "Key rotation verify-decrypt mismatch for card " + cardId + " — aborting (card left on retired key)");
                }
            } finally {
                Arrays.fill(roundTrip, (byte) 0);
            }

            // 4. Persist BOTH new ciphertext AND new key id in ONE row update (atomic in this tx).
            card.setEncryptedPan(reEncrypted.ciphertext());
            card.setEncryptionKeyId(reEncrypted.keyId());
            repository.saveCard(card);

            log.info("Card key rotated: card={}, tenant={}, from={}, to={}",
                    cardId, card.getTenantId(), retiredKeyId, activeKeyId);
            return RotationOutcome.ROTATED;
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
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
