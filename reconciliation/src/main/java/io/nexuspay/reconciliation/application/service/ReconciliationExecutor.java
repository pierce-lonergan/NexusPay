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

import java.util.List;

/**
 * Runs the reconciliation matching WORK in its own transaction, separate from the
 * run-lifecycle commits ({@link ReconciliationRunLifecycle}) and the failure
 * recorder ({@link ReconciliationFailureRecorder}).
 *
 * <p><strong>FIX (SEC-17, tx boundary):</strong> the work transaction must be able
 * to roll back its settlement-record / exception writes on a mid-pipeline failure
 * WITHOUT taking the run row down with it, and WITHOUT holding an uncommitted lock
 * on the run row that would block the {@code REQUIRES_NEW} failure recorder's
 * UPDATE-to-FAILED. Therefore:</p>
 * <ul>
 *   <li>the run is created ({@code PENDING}) and started ({@code RUNNING}) in their
 *       OWN committed transactions BEFORE this method runs (see
 *       {@link ReconciliationRunLifecycle}); this method does NOT touch the run
 *       row's status until the SUCCESS path's {@code run.complete()};</li>
 *   <li>this method is a {@code @Transactional} method on a SEPARATE bean from the
 *       orchestrator so the work transaction is a real proxy-bounded transaction,
 *       and the orchestrator (no longer {@code @Transactional}) can invoke the
 *       failure recorder on the catch path after this transaction has rolled
 *       back.</li>
 * </ul>
 *
 * @since SEC-17
 */
@Service
public class ReconciliationExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationExecutor.class);

    private final ThreeWayMatchingService matchingService;
    private final ExceptionManagementService exceptionService;
    private final ReconciliationRepository repository;

    public ReconciliationExecutor(ThreeWayMatchingService matchingService,
                                  ExceptionManagementService exceptionService,
                                  ReconciliationRepository repository) {
        this.matchingService = matchingService;
        this.exceptionService = exceptionService;
        this.repository = repository;
    }

    /**
     * Performs matching for an already-{@code RUNNING} run and, on success, commits
     * the {@code COMPLETED} terminal state atomically with the work. The run is NOT
     * touched on the failure path: any throwable propagates and the caller's catch
     * delegates the {@code FAILED} transition to {@link ReconciliationFailureRecorder}.
     */
    @Transactional
    public ReconciliationRun execute(ReconciliationRun run) {
        // Step 1: Fetch settlement records (the run is already committed RUNNING).
        List<SettlementRecord> settlements = repository.findSettlementRecordsByRunId(run.getId());

        // Step 2: Three-way matching
        log.info("Running three-way matching for run: {}, records: {}", run.getId(), settlements.size());
        List<MatchResult> results = matchingService.reconcile(settlements);

        // Step 3: Persist updated settlement records (match status updated by matching service)
        repository.saveAllSettlementRecords(settlements);

        // Step 4: Create exceptions for non-matched records
        List<ReconciliationException> exceptions = exceptionService.createExceptions(
                run, results, settlements);

        // Step 5: Complete the run with summary stats. Buckets PARTITION the
        // records (total = matched + unmatched + exceptions) so PARTIAL
        // (missing-ledger) lands in exceptions instead of vanishing (B-008).
        int matched = (int) results.stream().filter(MatchResult::isSuccessful).count();
        int unmatched = (int) results.stream()
                .filter(r -> r.status() == MatchResult.Status.UNMATCHED).count();
        int exceptionCount = settlements.size() - matched - unmatched; // EXCEPTION + PARTIAL
        run.complete(settlements.size(), matched, unmatched, exceptionCount);
        repository.saveRun(run);

        log.info("Reconciliation run completed: id={}, total={}, matched={}, matchRate={}%",
                run.getId(), run.getTotalRecords(), run.getMatchedCount(), run.matchRate());

        // Reference the created exceptions so a future caller could surface them; the
        // count is already reflected in the run stats above.
        log.debug("Persisted {} reconciliation exception(s) for run {}", exceptions.size(), run.getId());

        return run;
    }
}
