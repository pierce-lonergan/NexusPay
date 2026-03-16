package io.nexuspay.gateway.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Spring Data JPA repository for {@link PaymentTokenEntity}.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
public interface JpaPaymentTokenRepository extends JpaRepository<PaymentTokenEntity, String> {

    List<PaymentTokenEntity> findBySessionId(String sessionId);

    @Modifying
    @Query("UPDATE PaymentTokenEntity e SET e.used = true WHERE e.id = :id")
    void markUsed(@Param("id") String id);
}
