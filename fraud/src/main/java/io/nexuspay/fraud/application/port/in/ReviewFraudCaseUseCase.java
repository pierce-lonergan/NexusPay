package io.nexuspay.fraud.application.port.in;

import io.nexuspay.fraud.domain.model.RiskAssessment;

import java.util.List;
import java.util.UUID;

/**
 * Inbound port for reviewing fraud cases flagged for manual review.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public interface ReviewFraudCaseUseCase {

    /**
     * Lists assessments with PENDING_REVIEW status for a tenant.
     */
    List<RiskAssessment> listPendingReviews(String tenantId, int limit);

    /**
     * Retrieves a specific assessment by ID.
     */
    RiskAssessment getAssessment(UUID assessmentId, String tenantId);

    /**
     * Approves a REVIEW assessment, allowing the payment to proceed.
     */
    RiskAssessment approveAssessment(UUID assessmentId, String tenantId, String reviewedBy);

    /**
     * Rejects a REVIEW assessment, blocking the payment.
     */
    RiskAssessment rejectAssessment(UUID assessmentId, String tenantId, String reviewedBy);
}
