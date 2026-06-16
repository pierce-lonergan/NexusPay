package io.nexuspay.reconciliation.application.service;

import io.nexuspay.reconciliation.application.port.out.ReconciliationRepository;
import io.nexuspay.reconciliation.domain.ReconciliationRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits a reconciliation run's lifecycle transitions ({@code PENDING} create,
 * {@code RUNNING} start) in their OWN committed transactions, BEFORE any child
 * writes (parse-failure exceptions, settlement records) or the long matching
 * work transaction run.
 *
 * <p><strong>FIX (SEC-17, lost-failure-record / self-deadlock):</strong> the run
 * row's primary key (a {@code reconciliation_runs.id} application-assigned String)
 * must be COMMITTED before two later writers can touch it on a separate
 * connection:</p>
 * <ul>
 *   <li>{@link ParseFailureRecorder} inserts {@code PARSE_ERROR} exceptions in a
 *       {@code REQUIRES_NEW} transaction whose FK ({@code reconciliation_run_id}
 *       → {@code reconciliation_runs(id)}) must resolve; under READ COMMITTED an
 *       FK insert blocks on an uncommitted parent row, so the parent run row must
 *       already be committed.</li>
 *   <li>{@link ReconciliationFailureRecorder} marks the run {@code FAILED} via an
 *       UPDATE-by-id in a {@code REQUIRES_NEW} transaction on the catch path; an
 *       UPDATE only finds a COMMITTED row (the suspended outer/work transaction's
 *       uncommitted INSERT is invisible to it), and re-inserting the same PK would
 *       self-deadlock against the outer's still-open same-PK insert (a cycle
 *       Postgres cannot break — the outer is blocked on JVM control flow, not a
 *       lock). Committing PENDING + RUNNING here makes the failure path a clean,
 *       non-blocking UPDATE.</li>
 * </ul>
 *
 * <p>This is a <strong>separate Spring bean</strong> from the ingestion service
 * and orchestrator on purpose — {@code REQUIRES_NEW} is honored only when the call
 * crosses a proxy boundary, so it must NOT be a self-invocation.</p>
 *
 * @since SEC-17
 */
@Component
public class ReconciliationRunLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRunLifecycle.class);

    private final ReconciliationRepository repository;

    public ReconciliationRunLifecycle(ReconciliationRepository repository) {
        this.repository = repository;
    }

    /**
     * Creates a new {@code PENDING} run and commits it in its OWN transaction so the
     * run row (and its primary key) is durable before any settlement records or
     * parse-failure exceptions are written. Returns the committed run.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReconciliationRun createAndCommit(String tenantId, String provider, String fileName) {
        ReconciliationRun run = ReconciliationRun.create(tenantId, provider, fileName);
        repository.saveRun(run);
        log.debug("Committed PENDING run {} in its own transaction (durable before work)", run.getId());
        return run;
    }

    /**
     * Transitions a (already-committed) run to {@code RUNNING} and commits that
     * transition in its OWN transaction. Committing the {@code RUNNING} state here —
     * rather than inside the matching work transaction — means the work transaction
     * never holds an uncommitted lock on the run row, so the {@code REQUIRES_NEW}
     * {@link ReconciliationFailureRecorder} can UPDATE it to {@code FAILED} without
     * blocking on the work transaction's row lock.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startAndCommit(ReconciliationRun run) {
        run.start();
        repository.saveRun(run);
        log.debug("Committed RUNNING transition for run {} in its own transaction", run.getId());
    }
}
