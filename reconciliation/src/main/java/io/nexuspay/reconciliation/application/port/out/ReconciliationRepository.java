package io.nexuspay.reconciliation.application.port.out;

import io.nexuspay.reconciliation.domain.ReconciliationException;
import io.nexuspay.reconciliation.domain.ReconciliationRun;
import io.nexuspay.reconciliation.domain.SettlementRecord;

import java.util.List;
import java.util.Optional;

/**
 * Repository port for reconciliation persistence operations.
 *
 * @since 0.2.0 (Sprint 2.3)
 */
public interface ReconciliationRepository {

    ReconciliationRun saveRun(ReconciliationRun run);

    Optional<ReconciliationRun> findRunById(String id);

    List<ReconciliationRun> findRunsByTenant(String tenantId, int limit, int offset);

    SettlementRecord saveSettlementRecord(SettlementRecord record);

    List<SettlementRecord> saveAllSettlementRecords(List<SettlementRecord> records);

    List<SettlementRecord> findSettlementRecordsByRunId(String runId);

    ReconciliationException saveException(ReconciliationException exception);

    Optional<ReconciliationException> findExceptionById(String id);

    List<ReconciliationException> findExceptionsByRunId(String runId);

    List<ReconciliationException> findOpenExceptions(String tenantId, int limit, int offset);
}
