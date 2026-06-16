package io.nexuspay.fraud.adapter.in.rest;

import io.nexuspay.common.tenant.CallerTenant;
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
            @RequestParam(defaultValue = "50") int limit) {
        // SEC-23: tenant resolved from the authenticated principal, never from a client X-Tenant-Id
        // header (the old defaultValue="default" silently collapsed an absent header to "default").
        return ResponseEntity.ok(reviewUseCase.listPendingReviews(CallerTenant.require(), limit));
    }

    @GetMapping("/{assessmentId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<RiskAssessment> getAssessment(
            @PathVariable UUID assessmentId) {
        return ResponseEntity.ok(reviewUseCase.getAssessment(assessmentId, CallerTenant.require()));
    }

    @PostMapping("/{assessmentId}/approve")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<RiskAssessment> approveAssessment(
            @PathVariable UUID assessmentId,
            @AuthenticationPrincipal Jwt jwt) {
        // SEC-23: tenant resolved from the authenticated principal, never from a client X-Tenant-Id
        // header. The Jwt is retained ONLY for the reviewedBy audit field — not for authority.
        String reviewedBy = jwt != null ? jwt.getSubject() : "system";
        return ResponseEntity.ok(reviewUseCase.approveAssessment(assessmentId, CallerTenant.require(), reviewedBy));
    }

    @PostMapping("/{assessmentId}/reject")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<RiskAssessment> rejectAssessment(
            @PathVariable UUID assessmentId,
            @AuthenticationPrincipal Jwt jwt) {
        // SEC-23: tenant resolved from the authenticated principal, never from a client X-Tenant-Id
        // header. The Jwt is retained ONLY for the reviewedBy audit field — not for authority.
        String reviewedBy = jwt != null ? jwt.getSubject() : "system";
        return ResponseEntity.ok(reviewUseCase.rejectAssessment(assessmentId, CallerTenant.require(), reviewedBy));
    }
}
