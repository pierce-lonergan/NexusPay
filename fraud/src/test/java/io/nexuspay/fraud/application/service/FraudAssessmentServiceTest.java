package io.nexuspay.fraud.application.service;

import io.nexuspay.fraud.application.dto.PaymentContext;
import io.nexuspay.fraud.application.port.out.FraudAssessmentRepository;
import io.nexuspay.fraud.application.port.out.FraudEventPublisher;
import io.nexuspay.fraud.config.FraudProperties;
import io.nexuspay.fraud.domain.model.FraudAssessmentResult;
import io.nexuspay.fraud.domain.model.RiskAssessment;
import io.nexuspay.fraud.domain.model.RiskDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-027b: {@code FraudAssessmentService.assess} must be IDEMPOTENT on (tenant, idempotency-key).
 * A retried Idempotency-Key (network retry, billing dunning re-run, Temporal activity retry) must
 * NOT re-run the pipeline — one assessment row, one event, one velocity increment per logical
 * request. These tests pin: dedup-hit short-circuits the pipeline + event; a genuinely-new key
 * runs the full pipeline; a concurrent unique-index violation re-reads instead of double-writing;
 * a dedup-store error fails OPEN (assesses); and the disabled bypass is untouched.
 */
class FraudAssessmentServiceTest {

    private RuleEvaluationPipeline pipeline;
    private RiskScoringAggregator scoringAggregator;
    private DeviceFingerprintMatcher deviceMatcher;
    private FallbackFraudChainService fallbackChain;
    private FraudRuleManager ruleManager;
    private FraudAssessmentRepository repository;
    private FraudEventPublisher eventPublisher;
    private FraudProperties properties;
    private FraudAssessmentService service;

    @BeforeEach
    void setUp() {
        pipeline = mock(RuleEvaluationPipeline.class);
        scoringAggregator = mock(RiskScoringAggregator.class);
        deviceMatcher = mock(DeviceFingerprintMatcher.class);
        fallbackChain = mock(FallbackFraudChainService.class);
        ruleManager = mock(FraudRuleManager.class);
        repository = mock(FraudAssessmentRepository.class);
        eventPublisher = mock(FraudEventPublisher.class);
        properties = new FraudProperties(); // enabled=true by default

        // Default healthy pipeline: ALLOW, no rules/signals triggered.
        lenient().when(ruleManager.getActiveRulesForTenant(anyString())).thenReturn(List.of());
        lenient().when(pipeline.evaluate(any(), any())).thenReturn(
                new RuleEvaluationPipeline.NativeEvaluation(0, RiskDecision.ALLOW, List.of(), List.of()));
        lenient().when(deviceMatcher.matchAndAssess(any())).thenReturn(null);
        lenient().when(fallbackChain.assessWithFallback(any()))
                .thenReturn(new FallbackFraudChainService.FrmResult(null, "NATIVE_ONLY"));
        lenient().when(scoringAggregator.aggregate(anyInt(), any())).thenReturn(0);
        lenient().when(scoringAggregator.decide(anyInt())).thenReturn(RiskDecision.ALLOW);
        lenient().when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        // The persist/backstop path in assess() now uses saveAndFlush (MUST_FIX 2) so the unique
        // INSERT — and thus the constraint check — is flushed synchronously inside the try/catch,
        // matching real JPA semantics (a pre-assigned-UUID @Id entity merges + defers the INSERT, so
        // a plain save() would surface the violation OUTSIDE the catch at commit/outbox flush).
        lenient().when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(repository.findByTenantIdAndPaymentId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        service = new FraudAssessmentService(pipeline, scoringAggregator, deviceMatcher,
                fallbackChain, ruleManager, repository, eventPublisher, properties);
    }

    private static PaymentContext ctx(String tenant, String key) {
        return new PaymentContext(key, tenant, 5000, "USD", "cust_1", "a@b.com",
                "411111", "hash", "1.1.1.1", "US", "dev", Map.of(), Map.of(), key);
    }

    @Test
    void duplicateKey_returnsPriorAssessment_noPipeline_noSave_noEvent() {
        // First call runs and saves; thereafter the repo returns the prior row → a retry with the
        // SAME (tenant, key) MUST short-circuit: no pipeline, no second save, no second event.
        FraudAssessmentResult first = service.assess(ctx("T", "idem-1"));
        assertThat(first.decision()).isEqualTo(RiskDecision.ALLOW);
        verify(pipeline, times(1)).evaluate(any(), any());
        verify(repository, times(1)).saveAndFlush(any());
        verify(eventPublisher, times(1)).publishEvent(any(), any(), any(), any(), any());

        // Simulate the row now existing for the dedup read.
        RiskAssessment saved = RiskAssessment.create("T", "idem-1");
        saved.applyDecision(0, null, "NATIVE_ONLY", 0, RiskDecision.ALLOW);
        when(repository.findByTenantIdAndPaymentId("T", "idem-1")).thenReturn(Optional.of(saved));

        FraudAssessmentResult retry = service.assess(ctx("T", "idem-1"));

        assertThat(retry.decision()).isEqualTo(RiskDecision.ALLOW);
        verify(pipeline, times(1)).evaluate(any(), any());   // STILL 1 — retry did not run the pipeline
        verify(repository, times(1)).saveAndFlush(any());    // STILL 1 — no duplicate row
        verify(eventPublisher, times(1)).publishEvent(any(), any(), any(), any(), any()); // STILL 1 — no duplicate event
    }

    @Test
    void genuinelyNewKey_runsFullPipeline_andPersistsAndPublishes() {
        // A different idempotency key is a genuinely-new charge → full assessment, one row, one event.
        when(repository.findByTenantIdAndPaymentId("T", "idem-A")).thenReturn(Optional.empty());
        when(repository.findByTenantIdAndPaymentId("T", "idem-B")).thenReturn(Optional.empty());

        service.assess(ctx("T", "idem-A"));
        service.assess(ctx("T", "idem-B"));

        verify(pipeline, times(2)).evaluate(any(), any());   // both ran (not deduped against each other)
        verify(repository, times(2)).saveAndFlush(any());
        verify(eventPublisher, times(2)).publishEvent(any(), any(), any(), any(), any());
    }

    @Test
    void dedupScopedByTenant_sameKeyDifferentTenant_bothAssessed() {
        // The same idempotency key under two tenants must NOT collide — dedup is (tenant, key).
        when(repository.findByTenantIdAndPaymentId(anyString(), eq("idem-1"))).thenReturn(Optional.empty());

        service.assess(ctx("T1", "idem-1"));
        service.assess(ctx("T2", "idem-1"));

        verify(repository).findByTenantIdAndPaymentId("T1", "idem-1");
        verify(repository).findByTenantIdAndPaymentId("T2", "idem-1");
        verify(pipeline, times(2)).evaluate(any(), any());
    }

    @Test
    void concurrentRace_uniqueIndexViolationOnFlush_returnsResult_noSecondEvent_notPropagated() {
        // Two truly-concurrent retries both miss the read; the loser's INSERT trips the unique index.
        // MUST_FIX 2/3: the violation is raised at saveAndFlush (real JPA semantics — the
        // pre-assigned-UUID @Id entity defers the INSERT to flush, so the backstop MUST flush inside
        // the try/catch). The service must NOT propagate the exception and must NOT publish a second
        // event (exactly one row + one event per (tenant, key) belongs to the winner). The loser
        // returns its deterministic locally-computed result.
        //
        // NOTE: this is a unit test with the flush stubbed — it pins the contract (flush throws ->
        // swallowed -> deterministic result, no second event) but cannot prove the REAL Postgres
        // unique index raises at flush. The fraud module has no Testcontainers/@DataJpaTest base
        // (only the app module does), so the real-index integration test is a deliberate follow-up
        // rather than a fabricated, semantically-empty IT here.
        when(repository.findByTenantIdAndPaymentId("T", "idem-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any()))
                .thenThrow(new DuplicateKeyException(
                        "ERROR: duplicate key value violates unique constraint "
                                + "\"uq_fraud_assessments_tenant_idem\""));

        FraudAssessmentResult result = service.assess(ctx("T", "idem-1"));

        assertThat(result.decision()).isEqualTo(RiskDecision.ALLOW);
        verify(eventPublisher, never()).publishEvent(any(), any(), any(), any(), any()); // no second event
    }

    @Test
    void unrelatedIntegrityErrorOnFlush_propagates_notSwallowedAsBenignRace() {
        // SHOULD_FIX A: the backstop catch is narrowed to the SPECIFIC (tenant, idempotency-key)
        // uniqueness race. A genuine/unrelated integrity error (e.g. a NOT-NULL violation, a
        // DIFFERENT constraint) must NOT be silently swallowed as a benign duplicate — it must
        // propagate so the failure is surfaced, not masked behind a deterministic result.
        when(repository.findByTenantIdAndPaymentId("T", "idem-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "ERROR: null value in column \"decision\" violates not-null constraint"));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.assess(ctx("T", "idem-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(eventPublisher, never()).publishEvent(any(), any(), any(), any(), any());
    }

    @Test
    void dedupLookupFailure_failsOpen_assessmentStillRuns() {
        // A Valkey/DB error on the dedup read must NOT silently drop fraud screening — fail OPEN:
        // run the pipeline and persist. (A dropped screen is worse than a possible double-count.)
        when(repository.findByTenantIdAndPaymentId("T", "idem-1"))
                .thenThrow(new RuntimeException("store down"));  // dedup read throws

        FraudAssessmentResult result = service.assess(ctx("T", "idem-1"));

        assertThat(result.decision()).isEqualTo(RiskDecision.ALLOW);
        verify(pipeline, times(1)).evaluate(any(), any());  // assessment ran despite the lookup error
        verify(repository, times(1)).saveAndFlush(any());
    }

    @Test
    void disabledBypass_returnsBypass_withoutTouchingDedupStore() {
        // Regression guard: when fraud is disabled, assess returns a bypass result and NEVER touches
        // the dedup store or the pipeline.
        properties.setEnabled(false);

        FraudAssessmentResult result = service.assess(ctx("T", "idem-1"));

        assertThat(result.decision()).isEqualTo(RiskDecision.ALLOW);
        verify(repository, never()).findByTenantIdAndPaymentId(anyString(), anyString());
        verify(pipeline, never()).evaluate(any(), any());
        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
    }
}
