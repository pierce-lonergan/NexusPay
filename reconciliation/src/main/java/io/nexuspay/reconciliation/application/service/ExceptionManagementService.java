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

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the lifecycle of reconciliation exceptions.
 *
 * <p>Creates exceptions from match results, supports assignment to reviewers,
 * and handles resolution/write-off workflows.</p>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Service
public class ExceptionManagementService {

    private static final Logger log = LoggerFactory.getLogger(ExceptionManagementService.class);

    private final ReconciliationRepository repository;

    public ExceptionManagementService(ReconciliationRepository repository) {
        this.repository = repository;
    }

    /**
     * Creates exception records from match results that indicate problems.
     */
    @Transactional
    public List<ReconciliationException> createExceptions(ReconciliationRun run,
                                                           List<MatchResult> matchResults,
                                                           List<SettlementRecord> settlements) {
        List<ReconciliationException> exceptions = new ArrayList<>();

        // Creates a row for every problem record — EXCEPTION, UNMATCHED
        // (MISSING_PAYMENT) and PARTIAL (MISSING_LEDGER_ENTRY). Note the run's
        // exceptionCount STAT counts only EXCEPTION+PARTIAL (UNMATCHED has its own
        // bucket), so #rows == exceptionCount + unmatchedCount by design.
        for (int i = 0; i < matchResults.size(); i++) {
            MatchResult result = matchResults.get(i);
            if (result.status() == MatchResult.Status.EXCEPTION
                    || result.status() == MatchResult.Status.UNMATCHED
                    || result.status() == MatchResult.Status.PARTIAL) {

                SettlementRecord settlement = settlements.get(i);
                MatchResult.ExceptionType type = result.exceptionType() != null
                        ? result.exceptionType()
                        : MatchResult.ExceptionType.MISSING_PAYMENT;

                ReconciliationException ex = ReconciliationException.create(
                        run.getTenantId(),
                        run.getId(),
                        settlement.getId(),
                        type,
                        settlement.getAmount(),
                        null, // actual amount from payment — filled on investigation
                        result.description()
                );
                repository.saveException(ex);
                exceptions.add(ex);
            }
        }

        log.info("Created {} exceptions for run: {}", exceptions.size(), run.getId());
        return exceptions;
    }

    /**
     * Assigns an exception to a user for investigation.
     */
    @Transactional
    public ReconciliationException assign(String exceptionId, String userId) {
        ReconciliationException ex = repository.findExceptionById(exceptionId)
                .orElseThrow(() -> new IllegalArgumentException("Exception not found: " + exceptionId));

        ex.assignTo(userId);
        repository.saveException(ex);

        log.info("Exception {} assigned to {}", exceptionId, userId);
        return ex;
    }

    /**
     * Resolves an exception with notes explaining the resolution.
     */
    @Transactional
    public ReconciliationException resolve(String exceptionId, String notes) {
        ReconciliationException ex = repository.findExceptionById(exceptionId)
                .orElseThrow(() -> new IllegalArgumentException("Exception not found: " + exceptionId));

        ex.resolve(notes);
        repository.saveException(ex);

        log.info("Exception {} resolved", exceptionId);
        return ex;
    }

    /**
     * Writes off an exception (accepted loss / immaterial discrepancy).
     */
    @Transactional
    public ReconciliationException writeOff(String exceptionId, String notes) {
        ReconciliationException ex = repository.findExceptionById(exceptionId)
                .orElseThrow(() -> new IllegalArgumentException("Exception not found: " + exceptionId));

        ex.writeOff(notes);
        repository.saveException(ex);

        log.info("Exception {} written off", exceptionId);
        return ex;
    }

    /**
     * Lists open exceptions for the tenant, paginated.
     */
    public List<ReconciliationException> listOpenExceptions(String tenantId, int limit, int offset) {
        return repository.findOpenExceptions(tenantId, limit, offset);
    }
}
