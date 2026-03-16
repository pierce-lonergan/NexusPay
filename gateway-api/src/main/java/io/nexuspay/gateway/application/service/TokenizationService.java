package io.nexuspay.gateway.application.service;

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
    private final int maxTokenizeAttempts;
    private final Duration singleUseTokenExpiry;
    private final Duration multiUseTokenExpiry;

    public TokenizationService(
            PaymentSessionRepository sessionRepository,
            PaymentTokenRepository tokenRepository,
            @Value("${nexuspay.session.max-tokenize-attempts:10}") int maxTokenizeAttempts,
            @Value("${nexuspay.session.token-expiry:PT15M}") Duration singleUseTokenExpiry,
            @Value("${nexuspay.session.multi-use-token-expiry:P365D}") Duration multiUseTokenExpiry) {
        this.sessionRepository = sessionRepository;
        this.tokenRepository = tokenRepository;
        this.maxTokenizeAttempts = maxTokenizeAttempts;
        this.singleUseTokenExpiry = singleUseTokenExpiry;
        this.multiUseTokenExpiry = multiUseTokenExpiry;
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
        boolean singleUse = true; // SDK tokens are single-use by default
        Instant expiresAt = now.plus(singleUse ? singleUseTokenExpiry : multiUseTokenExpiry);

        var token = new PaymentToken(
                id, command.tenantId(), command.sessionId(), command.type(),
                command.cardLastFour(), command.cardBrand(),
                command.cardExpMonth(), command.cardExpYear(),
                null, // cardFingerprint — computed server-side from full card data
                command.tokenData(), null, // encryptionKeyId — set by encryption layer
                singleUse, false, expiresAt, now
        );

        tokenRepository.save(token);

        log.info("Tokenized payment method: id={}, session={}, type={}, brand={}",
                id, command.sessionId(), command.type(), command.cardBrand());

        return token;
    }
}
