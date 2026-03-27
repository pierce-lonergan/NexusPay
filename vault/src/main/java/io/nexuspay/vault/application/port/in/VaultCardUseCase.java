package io.nexuspay.vault.application.port.in;

import io.nexuspay.vault.domain.CardBrand;

import java.time.Instant;

/**
 * Use case for vaulting, retrieving, and deleting cards.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public interface VaultCardUseCase {

    VaultCardResult vaultCard(VaultCardCommand command);

    VaultedCardInfo getCard(String vaultTokenId, String tenantId);

    void deleteCard(String vaultTokenId, String tenantId);

    record VaultCardCommand(
            String tenantId,
            String pan,
            int expMonth,
            int expYear,
            String cardholderName
    ) {}

    record VaultCardResult(
            String vaultTokenId,
            String panLast4,
            CardBrand brand,
            String fingerprint
    ) {}

    record VaultedCardInfo(
            String vaultTokenId,
            String panLast4,
            String panBin,
            CardBrand brand,
            int expMonth,
            int expYear,
            String cardholderName,
            Instant createdAt
    ) {}
}
