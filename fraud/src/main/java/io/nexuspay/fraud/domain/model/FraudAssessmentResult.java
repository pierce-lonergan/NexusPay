package io.nexuspay.fraud.domain.model;

import java.util.List;
import java.util.UUID;

/**
 * Immutable result returned from the fraud assessment pipeline.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public record FraudAssessmentResult(
        UUID assessmentId,
        String paymentId,
        int aggregatedScore,
        RiskDecision decision,
        String frmProvider,
        List<String> triggeredRuleIds,
        List<RiskSignal> riskSignals,
        int latencyMs
) {
    public static FraudAssessmentResult from(RiskAssessment assessment) {
        return new FraudAssessmentResult(
                assessment.getId(),
                assessment.getPaymentId(),
                assessment.getAggregatedScore(),
                assessment.getDecision(),
                assessment.getFrmProvider(),
                List.copyOf(assessment.getTriggeredRuleIds()),
                List.copyOf(assessment.getRiskSignals()),
                assessment.getLatencyMs()
        );
    }
}
