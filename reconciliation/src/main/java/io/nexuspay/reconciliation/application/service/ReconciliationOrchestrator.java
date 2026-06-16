package io.nexuspay.reconciliation.application.service;

import io.nexuspay.reconciliation.domain.ReconciliationRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * Top-level orchestrator for end-to-end reconciliation flows.
 *
 * <p>Coordinates the full pipeline: ingest → start → match → create exceptions → complete run.</p>
 *
 * <p><strong>SEC-17 tx boundary:</strong> the orchestrator is intentionally NOT
 * {@code @Transactional}. The run row's lifecycle commits ({@code PENDING},
 * {@code RUNNING}) happen in their OWN transactions via
 * {@link ReconciliationRunLifecycle} BEFORE the matching {@link ReconciliationExecutor}
 * work transaction. This guarantees the run row (and its PK) is COMMITTED before the
 * work transaction runs, so a mid-pipeline failure lets {@link ReconciliationFailureRecorder}
 * mark the run {@code FAILED} (and write a {@code SYSTEM_ERROR} reason) via a non-blocking
 * UPDATE in a {@code REQUIRES_NEW} transaction — instead of self-deadlocking by re-inserting
 * the same PK the (old) shared outer transaction still held uncommitted.</p>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Service
public class ReconciliationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationOrchestrator.class);

    private final SettlementIngestionService ingestionService;
    private final ReconciliationRunLifecycle runLifecycle;
    private final ReconciliationExecutor executor;
    private final ReconciliationFailureRecorder failureRecorder;

    public ReconciliationOrchestrator(SettlementIngestionService ingestionService,
                                      ReconciliationRunLifecycle runLifecycle,
                                      ReconciliationExecutor executor,
                                      ReconciliationFailureRecorder failureRecorder) {
        this.ingestionService = ingestionService;
        this.runLifecycle = runLifecycle;
        this.executor = executor;
        this.failureRecorder = failureRecorder;
    }

    /**
     * Runs a full reconciliation from file upload.
     *
     * @param tenantId the tenant context
     * @param provider the PSP provider name
     * @param fileName the uploaded file name
     * @param input    the file content
     * @return the completed reconciliation run
     */
    public ReconciliationRun runFromUpload(String tenantId, String provider,
                                           String fileName, InputStream input) {
        // Step 1: Ingest (commits the PENDING run + settlement records + parse-failure
        // exceptions in their own transactions — durable before the work transaction).
        ReconciliationRun run = ingestionService.ingestFromStream(tenantId, provider, fileName, input);

        // Step 2: Execute reconciliation against the already-committed run.
        return executeReconciliation(run);
    }

    /**
     * Runs a full reconciliation from a remote file source.
     */
    public ReconciliationRun runFromSource(String tenantId, String provider,
                                           String source, String path) {
        ReconciliationRun run = ingestionService.ingest(tenantId, provider, source, path);
        return executeReconciliation(run);
    }

    private ReconciliationRun executeReconciliation(ReconciliationRun run) {
        // Commit the RUNNING transition in its OWN transaction so the work transaction
        // never holds an uncommitted lock on the run row (which would block the
        // REQUIRES_NEW failure recorder's UPDATE-to-FAILED).
        runLifecycle.startAndCommit(run);
        try {
            // The matching work runs in its OWN transaction (separate bean / proxy boundary).
            // On failure it rolls back its settlement-record / exception writes WITHOUT touching
            // the already-committed run row.
            return executor.execute(run);
        } catch (Exception e) {
            log.error("Reconciliation run failed: id={}", run.getId(), e);
            // SEC-17: mark the (already-committed RUNNING) run FAILED + write a durable
            // SYSTEM_ERROR failure-reason exception in a REQUIRES_NEW transaction (a SEPARATE
            // bean — proxy boundary required). Because the run row is already committed and the
            // work transaction (now rolled back) never locked it, this is a clean, non-blocking
            // UPDATE — not a same-PK re-INSERT that would self-deadlock. Mirrors ParseFailureRecorder.
            failureRecorder.recordFailure(run, e);
            throw e;
        }
    }
}
