package io.nexuspay.vault.domain;

/**
 * Request to generate a TAVV/CAVV cryptogram for an e-commerce transaction
 * using a provisioned network token.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public record CryptogramRequest(
        String vaultTokenId,
        String networkTokenId,
        long amount,
        String currency,
        String merchantId
) {}
