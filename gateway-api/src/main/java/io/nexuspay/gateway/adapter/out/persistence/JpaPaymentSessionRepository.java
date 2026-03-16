package io.nexuspay.gateway.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link PaymentSessionEntity}.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
public interface JpaPaymentSessionRepository extends JpaRepository<PaymentSessionEntity, String> {

    Optional<PaymentSessionEntity> findByClientSecret(String clientSecret);

    @Modifying
    @Query("UPDATE PaymentSessionEntity e SET e.status = :status, e.updatedAt = CURRENT_TIMESTAMP WHERE e.id = :id")
    void updateStatus(@Param("id") String id, @Param("status") String status);

    @Modifying
    @Query("UPDATE PaymentSessionEntity e SET e.tokenizeAttempts = e.tokenizeAttempts + 1, " +
            "e.updatedAt = CURRENT_TIMESTAMP WHERE e.id = :id")
    void incrementTokenizeAttempts(@Param("id") String id);
}
