package io.nexuspay.fraud.application.service;

import io.nexuspay.fraud.application.dto.PaymentContext;
import io.nexuspay.fraud.application.port.in.AssessFraudRiskUseCase;
import io.nexuspay.fraud.application.port.in.ReviewFraudCaseUseCase;
import io.nexuspay.fraud.application.port.out.FraudAssessmentRepository;
import io.nexuspay.fraud.application.port.out.FraudEventPublisher;
import io.nexuspay.fraud.config.FraudProperties;
import io.nexuspay.fraud.domain.model.*;
import io.nexuspay.common.tenant.TenantOwnership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final RequestFingerprinter requestFingerprinter;

    public FraudAssessmentService(RuleEvaluationPipeline ruleEvaluationPipeline,
                                   RiskScoringAggregator scoringAggregator,
                                   DeviceFingerprintMatcher deviceMatcher,
                                   FallbackFraudChainService fallbackChain,
                                   FraudRuleManager ruleManager,
                                   FraudAssessmentRepository assessmentRepository,
                                   FraudEventPublisher eventPublisher,
                                   FraudProperties fraudProperties,
                                   RequestFingerprinter requestFingerprinter) {
        this.ruleEvaluationPipeline = ruleEvaluationPipeline;
        this.scoringAggregator = scoringAggregator;
        this.deviceMatcher = deviceMatcher;
        this.fallbackChain = fallbackChain;
        this.ruleManager = ruleManager;
        this.assessmentRepository = assessmentRepository;
        this.eventPublisher = eventPublisher;
        this.fraudProperties = fraudProperties;
        this.requestFingerprinter = requestFingerprinter;
    }

    @Override
    @Transactional
    public FraudAssessmentResult assess(PaymentContext context) {
        if (!fraudProperties.isEnabled()) {
            return createBypassResult(context);
        }

        // B-027b IDEMPOTENT ASSESS: a retried Idempotency-Key (network retry, billing dunning re-run,
        // Temporal activity retry) must NOT re-run the pipeline — doing so double-counts fraud
        // velocity and writes a duplicate assessment row + event. Dedup on (tenantId, dedupKey)
        // BEFORE the pipeline so the velocity INCR is skipped on a dup. A read-store error here is
        // fail-OPEN (run the assessment) so a Valkey/DB outage cannot silently drop fraud screening.
        String dedupKey = context.dedupKey();

        // B-029-hardening: compute the KEYED request fingerprint ONCE, BEFORE the dedup lookup, so
        // both the dedup-hit compare AND the write path use the same value. Computing it first means
        // a key/compute failure fails CLOSED everywhere: FingerprintUnavailableException propagates
        // out of assess() (rolls back the @Transactional, no half-fingerprinted row) and can NEVER be
        // masked by a dedup hit returning a stale ALLOW.
        String currentFingerprint = requestFingerprinter.fingerprint(context);

        // B-029-hardening: set when an idempotency key was REUSED for a DIFFERENT charge (case (b)).
        // It re-runs the pipeline (so the caller gets a correct, fresh decision — never the stale
        // one) but gates OFF the persist + event publish, because the prior row legitimately occupies
        // the unique (tenant, dedupKey) slot. Re-assessing-without-persist is what STRUCTURALLY avoids
        // an unhandled uq_fraud_assessments_tenant_idem DataIntegrityViolation on this path.
        boolean reuseDetected = false;

        try {
            Optional<RiskAssessment> existing =
                    assessmentRepository.findByTenantIdAndPaymentId(context.tenantId(), dedupKey);
            if (existing.isPresent()) {
                RiskAssessment prior = existing.get();
                String storedFp = prior.getRequestFingerprint();
                if (storedFp == null) {
                    // (c) legacy pre-migration row: no fingerprint to compare. Fall back to the prior
                    // (idempotent) behavior — intentional, bounded back-compat. New rows always carry
                    // a non-null fingerprint, so an attacker cannot induce this branch on a new write.
                    log.info("Idempotent fraud assess HIT (legacy null fingerprint): tenant={}, key={} "
                            + "— returning prior assessment, no pipeline / no duplicate event",
                            context.tenantId(), dedupKey);
                    return FraudAssessmentResult.from(prior);
                }
                if (constantTimeEquals(storedFp, currentFingerprint)) {
                    // (a) same key + same request: genuine retry — short-circuit, return prior. No
                    // pipeline, no velocity INCR, no duplicate event (existing idempotent behavior).
                    log.info("Idempotent fraud assess HIT (fingerprint matched): tenant={}, key={} "
                            + "— returning prior assessment, no pipeline / no duplicate event",
                            context.tenantId(), dedupKey);
                    return FraudAssessmentResult.from(prior);
                }
                // (b) same key + DIFFERENT request: idempotency-key reuse across a different charge
                // (client bug or attack). SECURITY-RELEVANT. Do NOT return the prior (stale) decision;
                // RE-ASSESS. We never log the fingerprints (or their preimages). Fall through to run
                // the pipeline with reuseDetected set so we do not write a second row under the taken
                // unique key nor publish a duplicate event.
                log.warn("Idempotency key REUSED for a DIFFERENT request: tenant={}, key={} — stored "
                        + "fingerprint != current request fingerprint; NOT returning the prior "
                        + "decision, RE-ASSESSING. (client bug or attack)", context.tenantId(), dedupKey);
                reuseDetected = true;
            }
        } catch (RuntimeException e) {
            // A dedup STORE/IO error is fail-OPEN (run the assessment) — unchanged. NOTE this is a
            // store failure, distinct from a fingerprint MISMATCH (handled above) and a fingerprint
            // COMPUTE failure (thrown before this try, so it does NOT reach here). A
            // FingerprintUnavailableException would already have propagated out of assess().
            log.warn("Idempotency dedup lookup failed for tenant={}, key={} — failing OPEN (assessing)",
                    context.tenantId(), dedupKey, e);
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

        // B-029-hardening: stamp the keyed request fingerprint on every newly-built assessment so a
        // genuinely-new key persists it and the next retry can verify the request matches.
        assessment.setRequestFingerprint(currentFingerprint);

        // B-029-hardening: idempotency-key reuse across a DIFFERENT charge (case (b)). The prior row
        // legitimately owns the unique (tenant, dedupKey) slot, so we DO NOT persist a second row
        // (would violate uq_fraud_assessments_tenant_idem) and DO NOT publish a duplicate event. We
        // return the FRESHLY-computed decision for THIS charge — the security goal ("never serve the
        // stale decision for a different charge") is met; the reuse is recorded via the WARN above.
        // Because this branch NEVER calls saveAndFlush, the unique-violation path is STRUCTURALLY
        // unreachable here (not merely caught).
        if (reuseDetected) {
            log.info("Fraud RE-ASSESSMENT (idempotency-key reuse) complete WITHOUT persist: "
                    + "payment={}, score={}, decision={}, latency={}ms — prior row retained, no event",
                    context.paymentId(), aggregatedScore, finalDecision, latencyMs);
            return FraudAssessmentResult.from(assessment);
        }

        // B-027b race backstop: a truly-concurrent retry can slip past the read-through dedup above
        // (both reads miss, both run the pipeline). The unique index on (tenant_id, payment_id)
        // (V4023) makes the loser's INSERT throw — so exactly ONE row is persisted and exactly ONE
        // event is published per (tenant, idempotency-key), even under concurrency. The loser returns
        // its locally-computed (deterministic, same decision) result WITHOUT persisting a duplicate
        // row or publishing a second event. We deliberately do NOT re-query here: the save failure
        // marks this @Transactional rollback-only, so a follow-up read in the same transaction would
        // hit an aborted transaction — the common retry path is already served by the read-through
        // dedup at the top. (The loser's velocity INCR is separately suppressed by the SET-NX
        // first-seen marker in RuleEvaluationPipeline, so velocity is not double-counted either.)
        //
        // MUST_FIX 2: saveAndFlush (NOT save). FraudAssessmentEntity has a pre-assigned UUID @Id
        // (no @GeneratedValue/@Version, not Persistable), so SimpleJpaRepository.save() does
        // em.merge() and DEFERS the INSERT to flush/commit — the unique-index violation would then
        // surface OUTSIDE this try/catch (at event-outbox flush / commit) and propagate, possibly
        // attempting a duplicate event. Flushing here forces the INSERT (and the constraint check)
        // to happen at THIS call so the catch below reliably triggers for the loser.
        try {
            assessmentRepository.saveAndFlush(assessment);
        } catch (DataIntegrityViolationException dup) {
            // SHOULD_FIX A: only swallow the SPECIFIC (tenant, idempotency-key) uniqueness race.
            // A genuine/unrelated integrity error (NOT-NULL, a different constraint, FK, ...) must
            // NOT be silently treated as a benign duplicate — re-throw so it is not masked.
            if (!isTenantIdemConstraintViolation(dup)) {
                throw dup;
            }
            log.info("Concurrent fraud assess for tenant={}, key={} — unique index "
                    + "(uq_fraud_assessments_tenant_idem) rejected the duplicate row; returning the "
                    + "deterministic result, no second event", context.tenantId(), dedupKey, dup);
            return FraudAssessmentResult.from(assessment);
        }

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
        // SEC-23: tenant-scoped finder + 404 on absent OR wrong-tenant (no existence oracle; was
        // findById().filter(tenant==) -> IllegalArgumentException which had no handler -> 500).
        return TenantOwnership.require(
                assessmentRepository.findByIdAndTenantId(assessmentId, tenantId), "Assessment");
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

    /**
     * B-029-hardening: constant-time comparison of two fingerprint hex strings via
     * {@link MessageDigest#isEqual} on their UTF-8 bytes, so a partial-match timing leak cannot be
     * leveraged to forge a matching fingerprint and coax a stale ALLOW. Both arguments are non-null
     * at the call site (storedFp null is handled earlier; currentFingerprint never returns null).
     */
    private static boolean constantTimeEquals(String storedFp, String currentFingerprint) {
        return MessageDigest.isEqual(
                storedFp.getBytes(StandardCharsets.UTF_8),
                currentFingerprint.getBytes(StandardCharsets.UTF_8));
    }

    /** Name of the unique index that enforces (tenant, idempotency-key) uniqueness (V4023). */
    private static final String TENANT_IDEM_CONSTRAINT = "uq_fraud_assessments_tenant_idem";

    /**
     * SHOULD_FIX A: narrow the backstop catch to the SPECIFIC unique-constraint race so an unrelated
     * integrity error is not swallowed as a benign duplicate. Two complementary signals:
     * <ul>
     *   <li>Spring's {@link DuplicateKeyException} — the dedicated duplicate-key subtype the JPA
     *       exception translator raises for a unique-violation (PostgreSQL SQLSTATE 23505); or</li>
     *   <li>the {@link #TENANT_IDEM_CONSTRAINT} name appearing anywhere in the exception chain's
     *       messages — covers the case where the violation is surfaced as a bare
     *       {@link DataIntegrityViolationException} carrying the Postgres constraint name.</li>
     * </ul>
     * Any other {@link DataIntegrityViolationException} (a different unique index, a NOT-NULL/FK
     * violation, ...) returns {@code false} and is re-thrown by the caller.
     */
    private static boolean isTenantIdemConstraintViolation(DataIntegrityViolationException ex) {
        if (ex instanceof DuplicateKeyException) {
            return true;
        }
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains(TENANT_IDEM_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    private FraudAssessmentResult createBypassResult(PaymentContext context) {
        RiskAssessment bypass = RiskAssessment.create(context.tenantId(), context.paymentId());
        bypass.applyDecision(0, null, "DISABLED", 0, RiskDecision.ALLOW);
        bypass.setLatencyMs(0);
        return FraudAssessmentResult.from(bypass);
    }
}
