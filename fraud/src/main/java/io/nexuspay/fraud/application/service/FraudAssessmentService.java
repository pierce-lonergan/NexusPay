package io.nexuspay.fraud.application.service;

import io.nexuspay.fraud.application.dto.PaymentContext;
import io.nexuspay.fraud.application.port.in.AssessFraudRiskUseCase;
import io.nexuspay.fraud.application.port.in.ReviewFraudCaseUseCase;
import io.nexuspay.fraud.application.port.out.FraudAssessmentRepository;
import io.nexuspay.fraud.application.port.out.FraudEventPublisher;
import io.nexuspay.fraud.config.FraudProperties;
import io.nexuspay.fraud.domain.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Main fraud assessment orchestrator. Coordinates the full fraud evaluation pipeline:
 * native rule evaluation → FRM provider call → score aggregation → decision → persistence → event publishing.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Service
public class FraudAssessmentService implements AssessFraudRiskUseCase, ReviewFraudCaseUseCase {

    private static final Logger log = LoggerFactory.getLogger(FraudAssessmentService.class);

    private final RuleEvaluationPipeline ruleEvaluationPipeline;
    private final RiskScoringAggregator scoringAggregator;
    private final DeviceFingerprintMatcher deviceMatcher;
    private final FallbackFraudChainService fallbackChain;
    private final FraudRuleManager ruleManager;
    private final FraudAssessmentRepository assessmentRepository;
    private final FraudEventPublisher eventPublisher;
    private final FraudProperties fraudProperties;

    public FraudAssessmentService(RuleEvaluationPipeline ruleEvaluationPipeline,
                                   RiskScoringAggregator scoringAggregator,
                                   DeviceFingerprintMatcher deviceMatcher,
                                   FallbackFraudChainService fallbackChain,
                                   FraudRuleManager ruleManager,
                                   FraudAssessmentRepository assessmentRepository,
                                   FraudEventPublisher eventPublisher,
                                   FraudProperties fraudProperties) {
        this.ruleEvaluationPipeline = ruleEvaluationPipeline;
        this.scoringAggregator = scoringAggregator;
        this.deviceMatcher = deviceMatcher;
        this.fallbackChain = fallbackChain;
        this.ruleManager = ruleManager;
        this.assessmentRepository = assessmentRepository;
        this.eventPublisher = eventPublisher;
        this.fraudProperties = fraudProperties;
    }

    @Override
    @Transactional
    public FraudAssessmentResult assess(PaymentContext context) {
        if (!fraudProperties.isEnabled()) {
            return createBypassResult(context);
        }

        long startTime = System.currentTimeMillis();

        // 1. Load active rules (from cache)
        List<FraudRule> activeRules = ruleManager.getActiveRulesForTenant(context.tenantId());

        // 2. Native rule evaluation (Phase 1-3 of pipeline)
        RuleEvaluationPipeline.NativeEvaluation nativeEval =
                ruleEvaluationPipeline.evaluate(context, activeRules);

        // 3. Device fingerprint matching
        RiskSignal deviceSignal = deviceMatcher.matchAndAssess(context);

        // 4. External FRM provider call (with fallback chain)
        FallbackFraudChainService.FrmResult frmResult = fallbackChain.assessWithFallback(context);

        // 5. Adjust native score with device signal
        int adjustedNativeScore = nativeEval.nativeScore();
        if (deviceSignal != null) {
            adjustedNativeScore = Math.min(100, adjustedNativeScore + deviceSignal.score());
        }

        // 6. Score aggregation
        int aggregatedScore = scoringAggregator.aggregate(adjustedNativeScore, frmResult.score());
        RiskDecision finalDecision = scoringAggregator.decide(aggregatedScore);

        // If native evaluation demanded BLOCK, override the aggregated decision
        if (nativeEval.preDecision() == RiskDecision.BLOCK) {
            finalDecision = RiskDecision.BLOCK;
        }

        int latencyMs = (int) (System.currentTimeMillis() - startTime);

        // 7. Build and persist assessment
        RiskAssessment assessment = RiskAssessment.create(context.tenantId(), context.paymentId());
        assessment.applyDecision(adjustedNativeScore, frmResult.score(), frmResult.provider(),
                aggregatedScore, finalDecision);
        assessment.setLatencyMs(latencyMs);
        nativeEval.triggeredRuleIds().forEach(id -> assessment.addTriggeredRule(UUID.fromString(id)));
        nativeEval.signals().forEach(assessment::addRiskSignal);
        if (deviceSignal != null) assessment.addRiskSignal(deviceSignal);

        assessmentRepository.save(assessment);

        // 8. Publish events
        publishAssessmentEvent(assessment, context);

        log.info("Fraud assessment complete: payment={}, score={}, decision={}, latency={}ms",
                context.paymentId(), aggregatedScore, finalDecision, latencyMs);

        return FraudAssessmentResult.from(assessment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RiskAssessment> listPendingReviews(String tenantId, int limit) {
        return assessmentRepository.findPendingReviews(tenantId, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public RiskAssessment getAssessment(UUID assessmentId, String tenantId) {
        return assessmentRepository.findById(assessmentId)
                .filter(a -> a.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Assessment not found: " + assessmentId));
    }

    @Override
    @Transactional
    public RiskAssessment approveAssessment(UUID assessmentId, String tenantId, String reviewedBy) {
        RiskAssessment assessment = getAssessment(assessmentId, tenantId);
        assessment.approve(reviewedBy);
        assessmentRepository.save(assessment);

        eventPublisher.publishEvent("FraudAssessment", assessmentId.toString(),
                "FraudReviewApproved", Map.of(
                        "assessmentId", assessmentId.toString(),
                        "paymentId", assessment.getPaymentId(),
                        "reviewedBy", reviewedBy
                ), tenantId);

        log.info("Fraud assessment approved: id={}, payment={}, by={}",
                assessmentId, assessment.getPaymentId(), reviewedBy);
        return assessment;
    }

    @Override
    @Transactional
    public RiskAssessment rejectAssessment(UUID assessmentId, String tenantId, String reviewedBy) {
        RiskAssessment assessment = getAssessment(assessmentId, tenantId);
        assessment.reject(reviewedBy);
        assessmentRepository.save(assessment);

        eventPublisher.publishEvent("FraudAssessment", assessmentId.toString(),
                "FraudReviewRejected", Map.of(
                        "assessmentId", assessmentId.toString(),
                        "paymentId", assessment.getPaymentId(),
                        "reviewedBy", reviewedBy
                ), tenantId);

        log.info("Fraud assessment rejected: id={}, payment={}, by={}",
                assessmentId, assessment.getPaymentId(), reviewedBy);
        return assessment;
    }

    private void publishAssessmentEvent(RiskAssessment assessment, PaymentContext context) {
        String eventType = switch (assessment.getDecision()) {
            case ALLOW -> "FraudCheckPassed";
            case REVIEW -> "FraudCheckReview";
            case BLOCK -> "FraudCheckFailed";
        };

        eventPublisher.publishEvent("FraudAssessment", assessment.getId().toString(),
                eventType, Map.of(
                        "assessmentId", assessment.getId().toString(),
                        "paymentId", context.paymentId(),
                        "aggregatedScore", assessment.getAggregatedScore(),
                        "decision", assessment.getDecision().name(),
                        "frmProvider", assessment.getFrmProvider() != null ? assessment.getFrmProvider() : "NATIVE_ONLY",
                        "triggeredRules", assessment.getTriggeredRuleIds().size(),
                        "latencyMs", assessment.getLatencyMs()
                ), context.tenantId());
    }

    private FraudAssessmentResult createBypassResult(PaymentContext context) {
        RiskAssessment bypass = RiskAssessment.create(context.tenantId(), context.paymentId());
        bypass.applyDecision(0, null, "DISABLED", 0, RiskDecision.ALLOW);
        bypass.setLatencyMs(0);
        return FraudAssessmentResult.from(bypass);
    }
}
