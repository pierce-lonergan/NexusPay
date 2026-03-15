package io.nexuspay.reconciliation.application.service;

import io.nexuspay.reconciliation.application.port.out.ReconciliationRepository;
import io.nexuspay.reconciliation.domain.MatchResult;
import io.nexuspay.reconciliation.domain.ReconciliationException;
import io.nexuspay.reconciliation.domain.ReconciliationRun;
import io.nexuspay.reconciliation.domain.SettlementRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;

/**
 * Top-level orchestrator for end-to-end reconciliation flows.
 *
 * <p>Coordinates the full pipeline: ingest → match → create exceptions → complete run.</p>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Service
public class ReconciliationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationOrchestrator.class);

    private final SettlementIngestionService ingestionService;
    private final ThreeWayMatchingService matchingService;
    private final ExceptionManagementService exceptionService;
    private final ReconciliationRepository repository;

    public ReconciliationOrchestrator(SettlementIngestionService ingestionService,
                                      ThreeWayMatchingService matchingService,
                                      ExceptionManagementService exceptionService,
                                      ReconciliationRepository repository) {
        this.ingestionService = ingestionService;
        this.matchingService = matchingService;
        this.exceptionService = exceptionService;
        this.repository = repository;
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
    @Transactional
    public ReconciliationRun runFromUpload(String tenantId, String provider,
                                           String fileName, InputStream input) {
        // Step 1: Ingest
        ReconciliationRun run = ingestionService.ingestFromStream(tenantId, provider, fileName, input);

        // Step 2: Execute reconciliation
        return executeReconciliation(run);
    }

    /**
     * Runs a full reconciliation from a remote file source.
     */
    @Transactional
    public ReconciliationRun runFromSource(String tenantId, String provider,
                                           String source, String path) {
        ReconciliationRun run = ingestionService.ingest(tenantId, provider, source, path);
        return executeReconciliation(run);
    }

    private ReconciliationRun executeReconciliation(ReconciliationRun run) {
        try {
            run.start();
            repository.saveRun(run);

            // Step 2: Fetch settlement records
            List<SettlementRecord> settlements = repository.findSettlementRecordsByRunId(run.getId());

            // Step 3: Three-way matching
            log.info("Running three-way matching for run: {}, records: {}", run.getId(), settlements.size());
            List<MatchResult> results = matchingService.reconcile(settlements);

            // Step 4: Persist updated settlement records (match status updated by matching service)
            repository.saveAllSettlementRecords(settlements);

            // Step 5: Create exceptions for non-matched records
            List<ReconciliationException> exceptions = exceptionService.createExceptions(
                    run, results, settlements);

            // Step 6: Complete the run with summary stats
            int matched = (int) results.stream().filter(MatchResult::isSuccessful).count();
            int unmatched = (int) results.stream()
                    .filter(r -> r.status() == MatchResult.Status.UNMATCHED).count();
            run.complete(settlements.size(), matched, unmatched, exceptions.size());
            repository.saveRun(run);

            log.info("Reconciliation run completed: id={}, total={}, matched={}, matchRate={:.1f}%",
                    run.getId(), run.getTotalRecords(), run.getMatchedCount(), run.matchRate());

            return run;

        } catch (Exception e) {
            log.error("Reconciliation run failed: id={}", run.getId(), e);
            run.fail();
            repository.saveRun(run);
            throw e;
        }
    }
}
