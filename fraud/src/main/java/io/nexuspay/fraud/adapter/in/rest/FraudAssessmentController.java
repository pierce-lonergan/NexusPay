package io.nexuspay.fraud.adapter.in.rest;

import io.nexuspay.fraud.application.port.in.ReviewFraudCaseUseCase;
import io.nexuspay.fraud.domain.model.RiskAssessment;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for fraud assessment review and decisioning.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@RestController
@RequestMapping("/v1/fraud/assessments")
public class FraudAssessmentController {

    private final ReviewFraudCaseUseCase reviewUseCase;

    public FraudAssessmentController(ReviewFraudCaseUseCase reviewUseCase) {
        this.reviewUseCase = reviewUseCase;
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<List<RiskAssessment>> listPendingReviews(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(reviewUseCase.listPendingReviews(tenantId, limit));
    }

    @GetMapping("/{assessmentId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<RiskAssessment> getAssessment(
            @PathVariable UUID assessmentId,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return ResponseEntity.ok(reviewUseCase.getAssessment(assessmentId, tenantId));
    }

    @PostMapping("/{assessmentId}/approve")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<RiskAssessment> approveAssessment(
            @PathVariable UUID assessmentId,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @AuthenticationPrincipal Jwt jwt) {
        String reviewedBy = jwt != null ? jwt.getSubject() : "system";
        return ResponseEntity.ok(reviewUseCase.approveAssessment(assessmentId, tenantId, reviewedBy));
    }

    @PostMapping("/{assessmentId}/reject")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<RiskAssessment> rejectAssessment(
            @PathVariable UUID assessmentId,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @AuthenticationPrincipal Jwt jwt) {
        String reviewedBy = jwt != null ? jwt.getSubject() : "system";
        return ResponseEntity.ok(reviewUseCase.rejectAssessment(assessmentId, tenantId, reviewedBy));
    }
}
