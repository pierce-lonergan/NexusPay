package io.nexuspay.payment.application.port.fx;

import io.nexuspay.payment.domain.fx.FxRateLock;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for FX rate lock persistence.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public interface FxRateLockRepository {

    FxRateLock save(FxRateLock lock);

    Optional<FxRateLock> findById(UUID id);

    Optional<FxRateLock> findByPaymentId(String paymentId);

    /**
     * Marks expired, unconsumed locks as consumed (cleanup).
     *
     * @return number of expired locks cleaned up
     */
    int cleanupExpiredLocks();
}
