package io.nexuspay.reconciliation.application.service;

import io.nexuspay.reconciliation.application.port.out.ReconciliationRepository;
import io.nexuspay.reconciliation.domain.MatchResult;
import io.nexuspay.reconciliation.domain.ReconciliationException;
import io.nexuspay.reconciliation.domain.ReconciliationRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits a reconciliation run's terminal {@code FAILED} state AND a durable
 * failure-reason {@code SYSTEM_ERROR} exception in their OWN committed
 * transaction.
 *
 * <p><strong>FIX (SEC-17, tx boundary + self-deadlock):</strong> when matching or
 * persistence throws inside the matching work transaction
 * ({@link ReconciliationExecutor#execute(ReconciliationRun)}), the orchestrator's
 * catch must commit a durable {@code FAILED} trace that survives the work
 * transaction's rollback. Crucially, the run row is created ({@code PENDING}) and
 * started ({@code RUNNING}) in their OWN committed transactions BEFORE the work
 * transaction (see {@link ReconciliationRunLifecycle}), and the work transaction
 * does NOT touch the run row's status on the failure path. So by the time this
 * recorder runs, {@code reconciliation_runs.id=X} is ALREADY COMMITTED and is NOT
 * locked by any open transaction.</p>
 *
 * <p>This recorder therefore performs a non-blocking UPDATE of the committed run
 * row to {@code FAILED}: {@code repository.saveRun(run)} resolves (assigned-id
 * entity, no {@code @Version}) to {@code em.merge()}, whose SELECT finds the
 * committed row and schedules an UPDATE — never a same-PK re-INSERT. The earlier
 * defect re-INSERTed the same PK that the (then-shared) outer transaction still
 * held uncommitted, self-deadlocking forever (the outer was blocked on JVM control
 * flow, so Postgres saw no lock cycle to break, and no {@code lock_timeout} was
 * configured → indefinite hang → LOST failure record).</p>
 *
 * <p>Running {@link #recordFailure(ReconciliationRun, Throwable)} in a
 * {@link Propagation#REQUIRES_NEW} transaction commits the {@code FAILED} UPDATE
 * and the {@code SYSTEM_ERROR} child exception (FK now resolvable — the run row is
 * committed) independently of the work transaction's rollback. This recorder is a
 * <strong>separate Spring bean</strong> from the orchestrator on purpose —
 * {@code REQUIRES_NEW} is honored only when the call crosses a proxy boundary, so
 * it must NOT be a self-invocation within {@code ReconciliationOrchestrator}.</p>
 *
 * @since SEC-17
 */
@Component
public class ReconciliationFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationFailureRecorder.class);

    /** Cap the failure-reason description so a pathological exception message cannot overflow the column. */
    private static final int MAX_REASON_LENGTH = 1000;

    private final ReconciliationRepository repository;

    public ReconciliationFailureRecorder(ReconciliationRepository repository) {
        this.repository = repository;
    }

    /**
     * Commits the run's terminal {@code FAILED} state AND a durable
     * {@code SYSTEM_ERROR} failure-reason exception in a brand-new transaction
     * that survives the caller's rollback. The {@code settlementRecordId} is null
     * (no specific record caused the run-level failure; the FK column is nullable
     * — same shape as {@link ParseFailureRecorder}'s {@code PARSE_ERROR}), so the
     * failure is queryable/investigable even after the main work rolls back.
     *
     * @param run   the reconciliation run that failed
     * @param cause the throwable that aborted the run
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(ReconciliationRun run, Throwable cause) {
        run.fail();                  // status=FAILED, completedAt=now
        // Non-blocking UPDATE of the already-committed run row (merge finds the committed
        // RUNNING row and updates it to FAILED). Committed in THIS tx, independent of the
        // rolled-back work. NOT a same-PK re-INSERT (which would self-deadlock on a still-open
        // outer/work insert — the defect this fix removes).
        repository.saveRun(run);

        ReconciliationException ex = ReconciliationException.create(
                run.getTenantId(),
                run.getId(),
                null, // no settlement_record_id: the failure is run-level, not row-level
                MatchResult.ExceptionType.SYSTEM_ERROR,
                null, // expectedAmount n/a
                null, // actualAmount n/a
                "Reconciliation run failed: "
                        + (cause == null ? "(unknown cause)" : cause.getClass().getSimpleName())
                        + ": " + truncate(cause == null ? null : cause.getMessage(), MAX_REASON_LENGTH));
        repository.saveException(ex);

        log.warn("Committed FAILED state + SYSTEM_ERROR failure-reason exception for run {} in a "
                + "separate transaction (survives downstream rollback)", run.getId());
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "(no message)";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
