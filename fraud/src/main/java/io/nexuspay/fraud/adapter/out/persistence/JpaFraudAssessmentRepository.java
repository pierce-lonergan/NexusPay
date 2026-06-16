package io.nexuspay.fraud.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for fraud assessments.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public interface JpaFraudAssessmentRepository extends JpaRepository<FraudAssessmentEntity, UUID> {

    Optional<FraudAssessmentEntity> findByPaymentId(String paymentId);

    Optional<FraudAssessmentEntity> findByTenantIdAndPaymentId(String tenantId, String paymentId);

    // SEC-23: tenant-scoped by-id lookup — empty when absent OR owned by another tenant.
    Optional<FraudAssessmentEntity> findByIdAndTenantId(UUID id, String tenantId);

    List<FraudAssessmentEntity> findByTenantIdAndReviewStatus(String tenantId, String reviewStatus, Pageable pageable);
}
