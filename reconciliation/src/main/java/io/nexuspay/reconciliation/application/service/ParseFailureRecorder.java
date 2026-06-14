package io.nexuspay.reconciliation.application.service;

import io.nexuspay.reconciliation.application.port.out.ReconciliationRepository;
import io.nexuspay.reconciliation.application.port.out.SettlementParserPort.ParseFailure;
import io.nexuspay.reconciliation.domain.MatchResult;
import io.nexuspay.reconciliation.domain.ReconciliationException;
import io.nexuspay.reconciliation.domain.ReconciliationRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Persists parse-failure rows ({@link ParseFailure}) as durable
 * {@code PARSE_ERROR} reconciliation exceptions in their OWN committed
 * transaction.
 *
 * <p><strong>FIX 2 (B-015, tx boundary):</strong> the REST entry point
 * ({@code ReconciliationController.createRun → ReconciliationOrchestrator.runFromUpload})
 * is {@code @Transactional} with the default {@code REQUIRED} propagation, and
 * {@link SettlementIngestionService} joins that same physical transaction. If
 * matching or persistence <em>downstream</em> of ingest throws, the orchestrator
 * marks the shared transaction rollback-only and rethrows — which would also roll
 * back the {@code PARSE_ERROR} exceptions written during ingest, making the money
 * rows vanish with no durable record (the original B-015 defect, relocated to the
 * transaction boundary).</p>
 *
 * <p>By running {@link #record(ReconciliationRun, List)} in a
 * {@link Propagation#REQUIRES_NEW} transaction, the parse-failure exceptions are
 * committed independently: a later rollback of the caller's transaction cannot
 * erase them. This recorder is a <strong>separate Spring bean</strong> from the
 * ingestion service on purpose — {@code REQUIRES_NEW} is honored only when the
 * call crosses a proxy boundary, so it must NOT be a self-invocation within
 * {@code SettlementIngestionService}.</p>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Component
public class ParseFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(ParseFailureRecorder.class);

    private final ReconciliationRepository repository;

    public ParseFailureRecorder(ReconciliationRepository repository) {
        this.repository = repository;
    }

    /**
     * Persists one OPEN {@code PARSE_ERROR} {@link ReconciliationException} per
     * unparseable row, in a brand-new transaction that commits independently of
     * the caller's transaction. The {@code settlementRecordId} is null (no record
     * exists for a parse failure; the FK column is nullable) and the description
     * carries the logical record index and raw row so the discrepancy is
     * investigable. No settlement row vanishes silently — not even when the
     * surrounding reconciliation transaction later rolls back.
     *
     * @param run      the reconciliation run the failures belong to
     * @param failures the rows that could not be parsed/validated
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ReconciliationRun run, List<ParseFailure> failures) {
        if (failures == null || failures.isEmpty()) {
            return;
        }
        for (ParseFailure failure : failures) {
            ReconciliationException ex = ReconciliationException.create(
                    run.getTenantId(),
                    run.getId(),
                    null, // no settlement_record_id: the row never became a record
                    MatchResult.ExceptionType.PARSE_ERROR,
                    null, // expectedAmount unknown — the row could not be parsed
                    null, // actualAmount unknown
                    "Unparseable settlement row at line " + failure.lineNumber()
                            + ": " + failure.reason() + " | raw=" + failure.rawLine()
            );
            repository.saveException(ex);
        }
        log.warn("Committed {} PARSE_ERROR exception(s) for run {} in a separate "
                        + "transaction (survives downstream rollback)",
                failures.size(), run.getId());
    }
}
