package io.nexuspay.gateway.application.port.out;

import io.nexuspay.gateway.domain.PaymentToken;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link PaymentToken} entities.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
public interface PaymentTokenRepository {

    PaymentToken save(PaymentToken token);

    Optional<PaymentToken> findById(String id);

    List<PaymentToken> findBySessionId(String sessionId);

    /**
     * Marks a token as used. For single-use tokens, this prevents reuse.
     *
     * @param id the token ID
     */
    void markUsed(String id);
}
