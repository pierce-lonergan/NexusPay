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

    /**
     * SEC-27: tenant-scoped by-id lookup for a reconciliation run. Returns the run only when it
     * belongs to {@code tenantId}; an absent OR foreign-tenant run yields an empty Optional so the
     * by-id read/mutation paths collapse "absent" and "wrong tenant" into a single not-found (no
     * cross-tenant existence oracle). Pair with {@code TenantOwnership.require}.
     */
    Optional<ReconciliationRun> findRunByIdAndTenantId(String id, String tenantId);

    List<ReconciliationRun> findRunsByTenant(String tenantId, int limit, int offset);

    SettlementRecord saveSettlementRecord(SettlementRecord record);

    List<SettlementRecord> saveAllSettlementRecords(List<SettlementRecord> records);

    List<SettlementRecord> findSettlementRecordsByRunId(String runId);

    /**
     * SEC-27: tenant-scoped settlement-records finder. Scopes records by the parent run's tenant so a
     * tenant-A caller can never read tenant-B's settlement lines via a foreign run id. The predicate
     * is pushed to SQL ({@code reconciliation_run_id = ? AND tenant_id = ?}) and is additionally
     * bounded by {@code limit}/{@code offset} (the by-run records endpoint was previously unbounded).
     */
    List<SettlementRecord> findSettlementRecordsByRunIdAndTenantId(String runId, String tenantId,
                                                                   int limit, int offset);

    ReconciliationException saveException(ReconciliationException exception);

    Optional<ReconciliationException> findExceptionById(String id);

    /**
     * SEC-27: tenant-scoped by-id lookup for an exception. Returns the exception only when it belongs
     * to {@code tenantId}; absent OR foreign -> empty Optional (no existence oracle). Pair with
     * {@code TenantOwnership.require} on the resolve/assign/write-off mutation paths so a tenant-A
     * caller cannot mutate a tenant-B exception by id.
     */
    Optional<ReconciliationException> findExceptionByIdAndTenantId(String id, String tenantId);

    List<ReconciliationException> findExceptionsByRunId(String runId);

    List<ReconciliationException> findOpenExceptions(String tenantId, int limit, int offset);
}
