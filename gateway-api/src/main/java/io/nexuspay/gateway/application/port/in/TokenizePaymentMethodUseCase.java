package io.nexuspay.gateway.application.port.in;

import io.nexuspay.gateway.domain.PaymentToken;

/**
 * Tokenizes a payment method submitted by the client-side SDK.
 * Card data arrives encrypted from the PCI iframe and is stored as a token.
 * The token ID is then used to confirm payment without the merchant server
 * ever handling raw card data.
 *
 * <p>Tokenization is rate-limited per session (configurable, default 10 attempts)
 * to prevent brute-force card testing attacks. When the limit is exceeded,
 * the session is locked (expired).
 *
 * @since 0.3.5 (Sprint 3.5)
 */
public interface TokenizePaymentMethodUseCase {

    /**
     * Tokenizes a payment method for the given session.
     *
     * @param command the tokenization parameters
     * @return the created payment token
     * @throws io.nexuspay.gateway.domain.SessionExpiredException if session is expired or locked
     * @throws io.nexuspay.gateway.domain.TokenizationRateLimitException if max attempts exceeded
     */
    PaymentToken tokenize(TokenizeCommand command);

    /**
     * @param sessionId    the session this tokenization belongs to
     * @param tenantId     the tenant
     * @param type         payment method type (card, apple_pay, google_pay, etc.)
     * @param tokenData    encrypted payment method data
     * @param cardLastFour last four digits of card number (null for non-card)
     * @param cardBrand    card brand (visa, mastercard, etc.; null for non-card)
     * @param cardExpMonth card expiration month (null for non-card)
     * @param cardExpYear  card expiration year (null for non-card)
     */
    record TokenizeCommand(
            String sessionId,
            String tenantId,
            String type,
            byte[] tokenData,
            String cardLastFour,
            String cardBrand,
            Integer cardExpMonth,
            Integer cardExpYear
    ) {
    }
}
