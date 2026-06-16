package io.nexuspay.gateway.application.service;

import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.gateway.application.port.in.CreatePaymentSessionUseCase;
import io.nexuspay.gateway.application.port.out.PaymentSessionRepository;
import io.nexuspay.gateway.domain.PaymentSession;
import io.nexuspay.gateway.domain.SessionExpiredException;
import io.nexuspay.common.domain.SessionToken;
import io.nexuspay.iam.application.service.SessionTokenIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Creates and manages payment sessions for the checkout SDK.
 *
 * <p>Uses <strong>lazy expiration</strong>: every read checks {@code isExpired()}
 * and updates the status to EXPIRED if past {@code expiresAt}. No background
 * sweeper is needed.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
@Service
public class PaymentSessionService implements CreatePaymentSessionUseCase {

    private static final Logger log = LoggerFactory.getLogger(PaymentSessionService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int CLIENT_SECRET_BYTES = 32;

    private final PaymentSessionRepository sessionRepository;
    private final SessionTokenIssuer sessionTokenIssuer;
    private final Duration defaultExpiry;

    public PaymentSessionService(
            PaymentSessionRepository sessionRepository,
            SessionTokenIssuer sessionTokenIssuer,
            @Value("${nexuspay.session.default-expiry:PT30M}") Duration defaultExpiry) {
        this.sessionRepository = sessionRepository;
        this.sessionTokenIssuer = sessionTokenIssuer;
        this.defaultExpiry = defaultExpiry;
    }

    @Override
    @Transactional
    public CreateSessionResult create(CreateSessionCommand command) {
        String id = PrefixedId.paymentSession();
        String clientSecret = generateClientSecret();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(defaultExpiry);

        List<String> paymentMethods = command.allowedPaymentMethods() != null
                && !command.allowedPaymentMethods().isEmpty()
                ? command.allowedPaymentMethods()
                : List.of("card");

        var session = new PaymentSession(
                id, command.tenantId(), null, clientSecret,
                command.amount(), command.currency(), PaymentSession.STATUS_OPEN,
                command.customerId(), paymentMethods,
                command.successUrl(), command.cancelUrl(),
                command.branding(), command.metadata(),
                0, expiresAt, now, now
        );

        sessionRepository.save(session);

        // INT-3: the issued JWT carries the originating key's SERVER-DERIVED mode (command.live()) so the
        // SessionTokenAuthenticationFilter re-derives the request PaymentMode at SDK checkout — a
        // test-mode session can never reach the real PSP.
        SessionToken token = sessionTokenIssuer.issueToken(id, command.tenantId(), command.live());

        log.info("Created payment session: id={}, tenant={}, live={}, amount={} {}, expiresAt={}",
                id, command.tenantId(), command.live(), command.amount(), command.currency(), expiresAt);

        return new CreateSessionResult(session, token);
    }

    /**
     * Retrieves a session by ID with lazy expiration check.
     * If the session is past its expiry, it is marked as expired.
     */
    @Transactional
    public Optional<PaymentSession> findById(String id) {
        return sessionRepository.findById(id)
                .map(this::applyLazyExpiration);
    }

    /**
     * Retrieves a session by client secret with lazy expiration check.
     */
    @Transactional
    public Optional<PaymentSession> findByClientSecret(String clientSecret) {
        return sessionRepository.findByClientSecret(clientSecret)
                .map(this::applyLazyExpiration);
    }

    /**
     * Force-expires a session immediately.
     */
    @Transactional
    public void expireSession(String id) {
        var session = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        if (PaymentSession.STATUS_OPEN.equals(session.getStatus())) {
            sessionRepository.updateStatus(id, PaymentSession.STATUS_EXPIRED);
            log.info("Force-expired session: id={}", id);
        }
    }

    /**
     * Marks a session as complete with the given payment intent ID.
     */
    @Transactional
    public void completeSession(String id, String paymentIntentId) {
        var session = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        if (session.isExpired()) {
            throw new SessionExpiredException(id);
        }
        session.markComplete(paymentIntentId);
        sessionRepository.save(session);
        log.info("Completed session: id={}, paymentIntent={}", id, paymentIntentId);
    }

    /**
     * Lazy expiration: if a session is past its expiry time but still marked as open,
     * update its status to expired.
     */
    private PaymentSession applyLazyExpiration(PaymentSession session) {
        if (PaymentSession.STATUS_OPEN.equals(session.getStatus()) && session.isExpired()) {
            sessionRepository.updateStatus(session.getId(), PaymentSession.STATUS_EXPIRED);
            session.markExpired();
            log.debug("Lazy-expired session: id={}", session.getId());
        }
        return session;
    }

    private String generateClientSecret() {
        byte[] bytes = new byte[CLIENT_SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
