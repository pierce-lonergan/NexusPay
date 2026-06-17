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

    /**
     * SEC-27: tenant-scoped by-id lookup. Empty result means "absent OR owned by another tenant" —
     * pair with {@code TenantOwnership.require} so a foreign lock id 404s without an existence oracle.
     */
    Optional<FxRateLock> findByIdAndTenantId(UUID id, String tenantId);

    Optional<FxRateLock> findByPaymentId(String paymentId);

    /**
     * Marks expired, unconsumed locks as consumed (cleanup).
     *
     * @return number of expired locks cleaned up
     */
    int cleanupExpiredLocks();
}
