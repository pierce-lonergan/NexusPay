package io.nexuspay.fraud.application.port.out;

import io.nexuspay.fraud.domain.model.RiskAssessment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for fraud assessment persistence.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public interface FraudAssessmentRepository {

    RiskAssessment save(RiskAssessment assessment);

    Optional<RiskAssessment> findById(UUID id);

    Optional<RiskAssessment> findByPaymentId(String paymentId);

    List<RiskAssessment> findPendingReviews(String tenantId, int limit);
}
