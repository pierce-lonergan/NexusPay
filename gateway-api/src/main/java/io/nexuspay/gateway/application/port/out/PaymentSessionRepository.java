package io.nexuspay.gateway.application.port.out;

import io.nexuspay.gateway.domain.PaymentSession;

import java.util.Optional;

/**
 * Persistence port for {@link PaymentSession} entities.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
public interface PaymentSessionRepository {

    PaymentSession save(PaymentSession session);

    Optional<PaymentSession> findById(String id);

    Optional<PaymentSession> findByClientSecret(String clientSecret);

    /**
     * Updates the session status. Used for marking sessions as complete or expired.
     *
     * @param id     the session ID
     * @param status the new status
     */
    void updateStatus(String id, String status);

    /**
     * Atomically increments the tokenize attempt counter.
     *
     * @param id the session ID
     * @return the new attempt count after increment
     */
    int incrementTokenizeAttempts(String id);
}
