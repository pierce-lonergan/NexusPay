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
 * @param mandatoryReview      MUST_FIX FIX 3: a COMPLIANCE/geography REVIEW (unknown
 *                             server-authoritative geography on a cross-border-capable flow)
 *                             that must hold capture on EVERY rail — including server-initiated
 *                             recurring/workflow rails, where an ordinary fraud {@link #holdCapture}
 *                             is intentionally NOT held (M1 dunning policy). This is the OFAC
 *                             blind-spot guard: unlike a fraud REVIEW, it is never downgraded by
 *                             screening mode. It is set ONLY for the compliance unknown-geo branch,
 *                             not for ordinary fraud REVIEW.
 */
public record GateDecision(
        boolean holdCapture,
        RiskDecision fraudDecision,
        UUID fraudAssessmentId,
        boolean reportingRequired,
        boolean enhancedDueDiligence,
        boolean mandatoryReview
) {
    /** Backward-compatible 5-arg constructor: ordinary (fraud) decision, no mandatory compliance review. */
    public GateDecision(boolean holdCapture, RiskDecision fraudDecision, UUID fraudAssessmentId,
                        boolean reportingRequired, boolean enhancedDueDiligence) {
        this(holdCapture, fraudDecision, fraudAssessmentId, reportingRequired, enhancedDueDiligence, false);
    }

    /**
     * A capture-held REVIEW produced because the fraud engine itself errored on a
     * server-initiated rail (fail-to-REVIEW, not fail-open). No assessment was persisted,
     * so {@code fraudAssessmentId} is null. This is a fraud-path hold, NOT a mandatory
     * compliance review.
     */
    public static GateDecision heldOnError(boolean reportingRequired, boolean enhancedDueDiligence) {
        return new GateDecision(true, RiskDecision.REVIEW, null, reportingRequired, enhancedDueDiligence, false);
    }

    /**
     * FIX 3: a mandatory COMPLIANCE/geography REVIEW (unknown server-authoritative geography on a
     * cross-border-capable flow). Capture is held on ALL rails — the gate authorizes (so a
     * legitimate charge is not silently declined) but the OFAC review cannot be skipped on a
     * server rail. No fraud assessment is associated.
     */
    public static GateDecision complianceReview(boolean reportingRequired, boolean enhancedDueDiligence) {
        return new GateDecision(true, RiskDecision.REVIEW, null, reportingRequired, enhancedDueDiligence, true);
    }
}
