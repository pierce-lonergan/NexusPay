package io.nexuspay.fraud.application.service;

import io.nexuspay.fraud.config.FraudProperties;
import io.nexuspay.fraud.domain.model.RiskDecision;
import org.springframework.stereotype.Service;

/**
 * Combines native rule scores with FRM provider scores using weighted aggregation.
 *
 * <p>Formula: {@code finalScore = (nativeWeight × nativeScore) + (frmWeight × frmScore)}</p>
 * <p>When FRM is unavailable, native score is used at 100% weight.</p>
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Service
public class RiskScoringAggregator {

    private final FraudProperties fraudProperties;

    public RiskScoringAggregator(FraudProperties fraudProperties) {
        this.fraudProperties = fraudProperties;
    }

    /**
     * Aggregates native and FRM scores into a final score.
     *
     * @param nativeScore native rule engine score (0-100)
     * @param frmScore    FRM provider score (0-100), or null if FRM unavailable
     * @return aggregated score (0-100)
     */
    public int aggregate(int nativeScore, Integer frmScore) {
        if (frmScore == null) {
            // FRM unavailable — use native score at full weight
            return Math.max(0, Math.min(100, nativeScore));
        }

        double nativeWeight = fraudProperties.getScoring().getNativeWeight();
        double frmWeight = fraudProperties.getScoring().getFrmWeight();

        double weighted = (nativeWeight * nativeScore) + (frmWeight * frmScore);
        return Math.max(0, Math.min(100, (int) Math.round(weighted)));
    }

    /**
     * Determines the final risk decision based on the aggregated score.
     *
     * @param aggregatedScore the combined score (0-100)
     * @return ALLOW, REVIEW, or BLOCK
     */
    public RiskDecision decide(int aggregatedScore) {
        if (aggregatedScore >= fraudProperties.getNativeRules().getDefaultBlockThreshold()) {
            return RiskDecision.BLOCK;
        }
        if (aggregatedScore >= fraudProperties.getNativeRules().getDefaultReviewThreshold()) {
            return RiskDecision.REVIEW;
        }
        return RiskDecision.ALLOW;
    }
}
