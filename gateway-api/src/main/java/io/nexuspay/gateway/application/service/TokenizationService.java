package io.nexuspay.gateway.application.service;

import io.nexuspay.common.crypto.EncryptionPort;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.gateway.application.port.in.TokenizePaymentMethodUseCase;
import io.nexuspay.gateway.application.port.out.PaymentSessionRepository;
import io.nexuspay.gateway.application.port.out.PaymentTokenRepository;
import io.nexuspay.gateway.domain.PaymentSession;
import io.nexuspay.gateway.domain.PaymentToken;
import io.nexuspay.gateway.domain.SessionExpiredException;
import io.nexuspay.gateway.domain.TokenizationRateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Tokenizes payment methods submitted by the checkout SDK.
 *
 * <p>Rate limits tokenization attempts per session (configurable, default 10).
 * When the limit is exceeded, the session is locked (marked expired) to prevent
 * brute-force card testing attacks.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
@Service
public class TokenizationService implements TokenizePaymentMethodUseCase {

    private static final Logger log = LoggerFactory.getLogger(TokenizationService.class);

    private final PaymentSessionRepository sessionRepository;
    private final PaymentTokenRepository tokenRepository;
    private final EncryptionPort encryption;
    private final int maxTokenizeAttempts;
    private final Duration singleUseTokenExpiry;

    public TokenizationService(
            PaymentSessionRepository sessionRepository,
            PaymentTokenRepository tokenRepository,
            EncryptionPort encryption,
            @Value("${nexuspay.session.max-tokenize-attempts:10}") int maxTokenizeAttempts,
            @Value("${nexuspay.session.token-expiry:PT15M}") Duration singleUseTokenExpiry) {
        this.sessionRepository = sessionRepository;
        this.tokenRepository = tokenRepository;
        this.encryption = encryption;
        this.maxTokenizeAttempts = maxTokenizeAttempts;
        this.singleUseTokenExpiry = singleUseTokenExpiry;
    }

    @Override
    @Transactional
    public PaymentToken tokenize(TokenizeCommand command) {
        // Load and validate session
        PaymentSession session = sessionRepository.findById(command.sessionId())
                .orElseThrow(() -> new SessionExpiredException(command.sessionId()));

        // Lazy expiration check
        if (session.isExpired()) {
            if (PaymentSession.STATUS_OPEN.equals(session.getStatus())) {
                sessionRepository.updateStatus(session.getId(), PaymentSession.STATUS_EXPIRED);
            }
            throw new SessionExpiredException(command.sessionId());
        }

        if (!session.isOpen()) {
            throw new SessionExpiredException(command.sessionId());
        }

        // Rate limit check — increment atomically
        int attempts = sessionRepository.incrementTokenizeAttempts(command.sessionId());
        if (attempts > maxTokenizeAttempts) {
            // Lock the session
            sessionRepository.updateStatus(command.sessionId(), PaymentSession.STATUS_EXPIRED);
            log.warn("Tokenization rate limit exceeded: session={}, attempts={}, max={}",
                    command.sessionId(), attempts, maxTokenizeAttempts);
            throw new TokenizationRateLimitException(command.sessionId(), maxTokenizeAttempts);
        }

        // Create token
        String id = PrefixedId.paymentToken();
        Instant now = Instant.now();
        // SDK tokens are single-use by default.
        Instant expiresAt = now.plus(singleUseTokenExpiry);

        // SEC-04 / B-004: NEVER persist a recoverable PAN. The SDK card frame
        // base64-encodes the raw PAN into token_data; CheckoutController forwards
        // those bytes verbatim. Storing them as-is left a reversible cleartext PAN
        // at rest (a DB dump / read-only SQLi / backup / insider read could
        // base64-decode it back to the live card number). Encrypt the inbound
        // bytes with AES-256-GCM via the (common) EncryptionPort — the same
        // contract the vault uses — so the stored value is IV-prefixed, GCM-
        // authenticated ciphertext that is non-reversible without the master key,
        // and record the key id so the secure invariant (encryption_key_id NOT
        // NULL) is observable. Any non-empty token_data is encrypted uniformly;
        // wallet network tokens (apple_pay/google_pay) are not PANs, but encrypting
        // them too is harmless and avoids a PAN-vs-wallet branch that could leak a
        // reversible value through the wrong path. Empty token_data (e.g.
        // bank_redirect) carries no secret, so it is left as-is with a null key.
        byte[] storedTokenData = command.tokenData();
        String encryptionKeyId = null;
        if (storedTokenData != null && storedTokenData.length > 0) {
            String keyId = encryption.currentKeyId();
            EncryptionPort.EncryptionResult enc = encryption.encrypt(storedTokenData, keyId);
            storedTokenData = enc.ciphertext();
            encryptionKeyId = enc.keyId();
        }

        var token = new PaymentToken(
                id, command.tenantId(), command.sessionId(), command.type(),
                command.cardLastFour(), command.cardBrand(),
                command.cardExpMonth(), command.cardExpYear(),
                null, // cardFingerprint — computed server-side from full card data
                storedTokenData, encryptionKeyId, // SEC-04: ciphertext + non-null key id for card-bearing tokens
                true, false, expiresAt, now
        );

        tokenRepository.save(token);

        log.info("Tokenized payment method: id={}, session={}, type={}, brand={}",
                id, command.sessionId(), command.type(), command.cardBrand());

        return token;
    }
}
