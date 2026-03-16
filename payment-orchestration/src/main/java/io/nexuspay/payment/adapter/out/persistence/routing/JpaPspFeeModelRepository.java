package io.nexuspay.payment.adapter.out.persistence.routing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for PSP fee model entities.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Repository
public interface JpaPspFeeModelRepository extends JpaRepository<PspFeeModelEntity, UUID> {

    List<PspFeeModelEntity> findByTenantIdAndCurrency(String tenantId, String currency);

    List<PspFeeModelEntity> findByTenantId(String tenantId);

    @Query("""
            SELECT f FROM PspFeeModelEntity f
            WHERE f.tenantId = :tenantId
              AND f.pspConnector = :pspConnector
              AND f.currency = :currency
              AND f.effectiveFrom <= :date
              AND (f.effectiveTo IS NULL OR f.effectiveTo >= :date)
            ORDER BY f.effectiveFrom DESC
            """)
    Optional<PspFeeModelEntity> findEffective(
            @Param("tenantId") String tenantId,
            @Param("pspConnector") String pspConnector,
            @Param("currency") String currency,
            @Param("date") LocalDate date);
}
