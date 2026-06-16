package io.nexuspay.app;

import io.nexuspay.app.config.FaultInjectableThreeWayMatchingService;
import io.nexuspay.app.config.TestSecurityConfig;
import io.nexuspay.reconciliation.application.port.out.ReconciliationRepository;
import io.nexuspay.reconciliation.application.service.ReconciliationOrchestrator;
import io.nexuspay.reconciliation.application.service.ThreeWayMatchingService;
import io.nexuspay.reconciliation.domain.MatchResult;
import io.nexuspay.reconciliation.domain.ReconciliationException;
import io.nexuspay.reconciliation.domain.ReconciliationRun;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SEC-17 GATE: a reconciliation run that fails mid-pipeline must leave a DURABLE trace — the run row
 * is committed in {@code FAILED} status AND a {@code SYSTEM_ERROR} failure-reason exception is
 * persisted, both surviving the outer transaction's rollback.
 *
 * <p><strong>Fault:</strong> {@link ThreeWayMatchingService#reconcile(List)} is armed (via the shared
 * {@link FaultInjectableThreeWayMatchingService} double — NOT a context-forking {@code @MockBean}) to
 * throw AFTER the run has been started ({@code RUNNING}) inside the orchestrator's outer
 * {@code @Transactional} ({@code REQUIRED}) boundary. The orchestrator rethrows, marking the shared
 * transaction rollback-only.</p>
 *
 * <p><strong>Why it fails on the vulnerable code:</strong> the old catch did
 * {@code run.fail(); repository.saveRun(run);} INLINE in the rolled-back transaction, so the
 * {@code FAILED} state (and the run row itself, first written by the outer tx) vanished on rollback —
 * {@code findRunsByTenant} would return no FAILED run and {@code findExceptionsByRunId} no
 * {@code SYSTEM_ERROR} row. The SEC-17 fix delegates to {@code ReconciliationFailureRecorder} in a
 * {@code REQUIRES_NEW} transaction that commits independently before the rollback.</p>
 */
@Import(TestSecurityConfig.class)
@DisplayName("SEC-17 GATE: failed reconciliation run leaves a durable FAILED + SYSTEM_ERROR trace")
class ReconciliationFailureDurabilityIT extends IntegrationTestBase {

    @Autowired
    private ReconciliationOrchestrator orchestrator;

    @Autowired
    private ReconciliationRepository repository;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — SEC-17 durability IT self-skips (Testcontainers required)");
    }

    private static final String STRIPE_CSV =
            "id,amount,currency,fee,net,created,description,payment_intent\n"
                    + "txn_sec17,10000,usd,290,9710,2026-03-14,SEC-17 fault injection,pi_sec17\n";

    @Test
    @DisplayName("run.fail() + SYSTEM_ERROR exception survive the outer rollback")
    void failedRun_persistsDurableFailedStateAndReason() {
        // A tenant unique to this test run so findRunsByTenant returns ONLY this run.
        String tenantId = "sec17_" + UUID.randomUUID();
        RuntimeException boom = new IllegalStateException("matching engine exploded (SEC-17 fault)");

        // Arm the shared fault double on THIS thread; reconcile() runs synchronously on it, so the
        // orchestrator hits the throw inside its outer transaction. ALWAYS disarm in finally.
        FaultInjectableThreeWayMatchingService.armFault(boom);
        try {
            // Act: the original exception must propagate out of the @Transactional proxy.
            assertThatThrownBy(() -> orchestrator.runFromUpload(
                    tenantId, "stripe", "sec17.csv",
                    new ByteArrayInputStream(STRIPE_CSV.getBytes(StandardCharsets.UTF_8))))
                    .as("the run-failure cause must propagate (outer tx rolls back the work)")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SEC-17 fault");
        } finally {
            FaultInjectableThreeWayMatchingService.clearFault();
        }

        // The REQUIRES_NEW recorder committed the run id post-rollback; query it back by tenant.
        List<ReconciliationRun> runs = repository.findRunsByTenant(tenantId, 10, 0);
        assertThat(runs)
                .as("the FAILED run must be durable (committed by the REQUIRES_NEW recorder, "
                        + "not rolled back with the failed work)")
                .hasSize(1);

        ReconciliationRun run = runs.get(0);
        assertThat(run.getStatus())
                .as("the durable run must be in FAILED status")
                .isEqualTo(ReconciliationRun.RunStatus.FAILED);

        // The durable failure-reason record must exist and carry the cause.
        List<ReconciliationException> exceptions = repository.findExceptionsByRunId(run.getId());
        assertThat(exceptions)
                .as("a SYSTEM_ERROR failure-reason exception must be persisted for the failed run")
                .anySatisfy(ex -> {
                    assertThat(ex.getExceptionType()).isEqualTo(MatchResult.ExceptionType.SYSTEM_ERROR);
                    assertThat(ex.getDescription()).contains("SEC-17 fault");
                    assertThat(ex.getSettlementRecordId())
                            .as("a run-level failure reason carries no settlement record id")
                            .isNull();
                });
    }
}
