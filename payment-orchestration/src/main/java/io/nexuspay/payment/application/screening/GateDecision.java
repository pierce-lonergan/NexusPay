package io.nexuspay.payment.application.screening;

import io.nexuspay.fraud.domain.model.RiskDecision;

import java.util.UUID;

/**
 * Outcome of the {@link PreAuthorizationGate} for a payment that was NOT rejected.
 * (A BLOCK or a sanctioned country never produces a decision — the gate throws a
 * {@code PaymentException} instead, so the PSP is never called.)
 *
 * @param holdCapture          true when fraud returned REVIEW → the caller must
 *                             authorize without auto-capturing (manual capture)
 * @param fraudDecision        ALLOW or REVIEW (BLOCK never reaches here)
 * @param fraudAssessmentId    id of the persisted fraud assessment, for linkage
 * @param reportingRequired    cross-border regulatory reporting flagged
 * @param enhancedDueDiligence EDD flagged by compliance
 */
public record GateDecision(
        boolean holdCapture,
        RiskDecision fraudDecision,
        UUID fraudAssessmentId,
        boolean reportingRequired,
        boolean enhancedDueDiligence
) {
    /**
     * A capture-held REVIEW produced because the fraud engine itself errored on a
     * server-initiated rail (fail-to-REVIEW, not fail-open). No assessment was persisted,
     * so {@code fraudAssessmentId} is null.
     */
    public static GateDecision heldOnError(boolean reportingRequired, boolean enhancedDueDiligence) {
        return new GateDecision(true, RiskDecision.REVIEW, null, reportingRequired, enhancedDueDiligence);
    }
}
