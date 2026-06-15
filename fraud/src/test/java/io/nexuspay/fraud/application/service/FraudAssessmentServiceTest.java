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
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.util.Base64;
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
 *
 * <p>B-029-hardening: the dedup hit now also verifies a KEYED request fingerprint. Same key + same
 * fingerprint short-circuits; same key + DIFFERENT fingerprint RE-ASSESSES (without persisting a
 * second row / publishing a second event) and warns; a legacy NULL-fingerprint hit returns prior.
 * A REAL {@link RequestFingerprinter} (fixed test master key) is used so the actual canonicalization
 * + HMAC is exercised, not a mock.</p>
 */
class FraudAssessmentServiceTest {

    /** Fixed base64 of a 32-byte key so fingerprints are deterministic and real across the suite. */
    private static final String TEST_MASTER_KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private RuleEvaluationPipeline pipeline;
    private RiskScoringAggregator scoringAggregator;
    private DeviceFingerprintMatcher deviceMatcher;
    private FallbackFraudChainService fallbackChain;
    private FraudRuleManager ruleManager;
    private FraudAssessmentRepository repository;
    private FraudEventPublisher eventPublisher;
    private FraudProperties properties;
    private RequestFingerprinter fingerprinter;
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
        fingerprinter = new RequestFingerprinter(TEST_MASTER_KEY); // REAL HMAC, not a mock

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
                fallbackChain, ruleManager, repository, eventPublisher, properties, fingerprinter);
    }

    private static PaymentContext ctx(String tenant, String key) {
        return ctx(tenant, key, 5000, "USD", "cust_1", "hash");
    }

    private static PaymentContext ctx(String tenant, String key, long amount, String currency,
                                      String customerId, String cardHash) {
        return new PaymentContext(key, tenant, amount, currency, customerId, "a@b.com",
                "411111", cardHash, "1.1.1.1", "US", "dev", Map.of(), Map.of(), key);
    }

    /** Builds a stored prior assessment carrying the fingerprint of the given context. */
    private RiskAssessment priorWithFingerprintOf(String tenant, String key, PaymentContext fpSource) {
        RiskAssessment prior = RiskAssessment.create(tenant, key);
        prior.applyDecision(0, null, "NATIVE_ONLY", 0, RiskDecision.ALLOW);
        prior.setRequestFingerprint(fpSource == null ? null : fingerprinter.fingerprint(fpSource));
        return prior;
    }

    @Test
    void sameKey_sameFingerprint_shortCircuits_noPipeline_noSave_noEvent() {
        // First call runs and saves; thereafter the repo returns the prior row (carrying the SAME
        // fingerprint) → a retry with the SAME (tenant, key) + SAME request MUST short-circuit.
        PaymentContext first = ctx("T", "idem-1");
        FraudAssessmentResult firstResult = service.assess(first);
        assertThat(firstResult.decision()).isEqualTo(RiskDecision.ALLOW);
        verify(pipeline, times(1)).evaluate(any(), any());
        verify(repository, times(1)).saveAndFlush(any());
        verify(eventPublisher, times(1)).publishEvent(any(), any(), any(), any(), any());

        // The row now exists for the dedup read, carrying the fingerprint of the identical request.
        RiskAssessment saved = priorWithFingerprintOf("T", "idem-1", ctx("T", "idem-1"));
        when(repository.findByTenantIdAndPaymentId("T", "idem-1")).thenReturn(Optional.of(saved));

        FraudAssessmentResult retry = service.assess(ctx("T", "idem-1"));

        assertThat(retry.decision()).isEqualTo(RiskDecision.ALLOW);
        verify(pipeline, times(1)).evaluate(any(), any());   // STILL 1 — retry did not run the pipeline
        verify(repository, times(1)).saveAndFlush(any());    // STILL 1 — no duplicate row
        verify(eventPublisher, times(1)).publishEvent(any(), any(), any(), any(), any()); // STILL 1
    }

    @Test
    void sameKey_differentFingerprint_reAssesses_noSecondRow_noSecondEvent() {
        // Prior row carries the fingerprint of amount=5000; a retry reuses the SAME key for a
        // DIFFERENT charge (amount=9999). The service MUST re-run the pipeline (never serve the stale
        // decision) but MUST NOT write a second row (would violate the unique key) nor publish a
        // second event — the reuse is recorded only via the WARN log.
        RiskAssessment prior = priorWithFingerprintOf("T", "idem-1",
                ctx("T", "idem-1", 5000, "USD", "cust_1", "hash"));
        when(repository.findByTenantIdAndPaymentId("T", "idem-1")).thenReturn(Optional.of(prior));

        FraudAssessmentResult result = service.assess(
                ctx("T", "idem-1", 9999, "USD", "cust_1", "hash"));

        assertThat(result.decision()).isEqualTo(RiskDecision.ALLOW); // freshly-computed decision
        verify(pipeline, times(1)).evaluate(any(), any());           // RE-ASSESSED
        verify(repository, never()).saveAndFlush(any());             // NO second row under the taken key
        verify(eventPublisher, never()).publishEvent(any(), any(), any(), any(), any()); // NO second event
    }

    @Test
    void legacyNullFingerprint_hitReturnsPrior_noPipeline_noSecondSaveOrEvent() {
        // A pre-migration row has requestFingerprint == null. We cannot prove mismatch, so fall back
        // to the prior (idempotent) behavior — return prior, no re-run, no second save/event.
        RiskAssessment prior = priorWithFingerprintOf("T", "idem-1", null); // null fingerprint
        when(repository.findByTenantIdAndPaymentId("T", "idem-1")).thenReturn(Optional.of(prior));

        FraudAssessmentResult result = service.assess(ctx("T", "idem-1"));

        assertThat(result.decision()).isEqualTo(RiskDecision.ALLOW);
        verify(pipeline, never()).evaluate(any(), any());       // NOT re-run
        verify(repository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any(), any(), any(), any(), any());
    }

    @Test
    void genuinelyNewKey_runsFullPipeline_andPersistsWithNonNullFingerprint_andPublishes() {
        // A different idempotency key is a genuinely-new charge → full assessment, one row, one event.
        when(repository.findByTenantIdAndPaymentId("T", "idem-A")).thenReturn(Optional.empty());
        when(repository.findByTenantIdAndPaymentId("T", "idem-B")).thenReturn(Optional.empty());

        service.assess(ctx("T", "idem-A"));
        service.assess(ctx("T", "idem-B"));

        verify(pipeline, times(2)).evaluate(any(), any());   // both ran (not deduped against each other)
        verify(eventPublisher, times(2)).publishEvent(any(), any(), any(), any(), any());

        ArgumentCaptor<RiskAssessment> captor = ArgumentCaptor.forClass(RiskAssessment.class);
        verify(repository, times(2)).saveAndFlush(captor.capture());
        // Every persisted assessment carries a non-null 64-hex fingerprint (never the cleartext tuple).
        for (RiskAssessment persisted : captor.getAllValues()) {
            assertThat(persisted.getRequestFingerprint()).isNotNull();
            assertThat(persisted.getRequestFingerprint()).matches("^[0-9a-f]{64}$");
            assertThat(persisted.getRequestFingerprint()).doesNotContain("cust_1", "USD", "5000", "hash");
        }
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
