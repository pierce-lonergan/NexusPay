package io.nexuspay.payment.adapter.out.persistence.fx;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for FX rate lock entities.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public interface JpaFxRateLockRepository extends JpaRepository<FxRateLockEntity, UUID> {

    Optional<FxRateLockEntity> findByPaymentId(String paymentId);

    @Modifying
    @Query("UPDATE FxRateLockEntity e SET e.consumed = true, e.consumedAt = :now " +
           "WHERE e.consumed = false AND e.expiresAt < :now")
    int cleanupExpiredLocks(@Param("now") Instant now);
}
