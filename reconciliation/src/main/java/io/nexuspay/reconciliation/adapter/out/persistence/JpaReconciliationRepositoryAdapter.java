package io.nexuspay.reconciliation.adapter.out.persistence;

import io.nexuspay.reconciliation.application.port.out.ReconciliationRepository;
import io.nexuspay.reconciliation.domain.MatchResult;
import io.nexuspay.reconciliation.domain.ReconciliationException;
import io.nexuspay.reconciliation.domain.ReconciliationRun;
import io.nexuspay.reconciliation.domain.SettlementRecord;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * JPA-based implementation of the reconciliation repository port.
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Repository
public class JpaReconciliationRepositoryAdapter implements ReconciliationRepository {

    private final JpaRunRepository runRepo;
    private final JpaSettlementRecordRepository settlementRepo;
    private final JpaExceptionRepository exceptionRepo;

    public JpaReconciliationRepositoryAdapter(JpaRunRepository runRepo,
                                               JpaSettlementRecordRepository settlementRepo,
                                               JpaExceptionRepository exceptionRepo) {
        this.runRepo = runRepo;
        this.settlementRepo = settlementRepo;
        this.exceptionRepo = exceptionRepo;
    }

    @Override
    public ReconciliationRun saveRun(ReconciliationRun run) {
        ReconciliationRunEntity entity = toEntity(run);
        runRepo.save(entity);
        return run;
    }

    @Override
    public Optional<ReconciliationRun> findRunById(String id) {
        return runRepo.findById(id).map(this::toDomain);
    }

    @Override
    public List<ReconciliationRun> findRunsByTenant(String tenantId, int limit, int offset) {
        return runRepo.findByTenantIdOrderByCreatedAtDesc(tenantId,
                        PageRequest.of(offset / Math.max(limit, 1), Math.max(limit, 1)))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public SettlementRecord saveSettlementRecord(SettlementRecord record) {
        settlementRepo.save(toEntity(record));
        return record;
    }

    @Override
    public List<SettlementRecord> saveAllSettlementRecords(List<SettlementRecord> records) {
        List<SettlementRecordEntity> entities = records.stream().map(this::toEntity).collect(Collectors.toList());
        settlementRepo.saveAll(entities);
        return records;
    }

    @Override
    public List<SettlementRecord> findSettlementRecordsByRunId(String runId) {
        return settlementRepo.findByReconciliationRunId(runId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public ReconciliationException saveException(ReconciliationException exception) {
        exceptionRepo.save(toEntity(exception));
        return exception;
    }

    @Override
    public Optional<ReconciliationException> findExceptionById(String id) {
        return exceptionRepo.findById(id).map(this::toDomain);
    }

    @Override
    public List<ReconciliationException> findExceptionsByRunId(String runId) {
        return exceptionRepo.findByReconciliationRunId(runId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<ReconciliationException> findOpenExceptions(String tenantId, int limit, int offset) {
        return exceptionRepo.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, "OPEN",
                        PageRequest.of(offset / Math.max(limit, 1), Math.max(limit, 1)))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    // ---- Entity ↔ Domain mappers ----

    private ReconciliationRunEntity toEntity(ReconciliationRun run) {
        ReconciliationRunEntity e = new ReconciliationRunEntity();
        e.setId(run.getId());
        e.setTenantId(run.getTenantId());
        e.setProvider(run.getProvider());
        e.setFileName(run.getFileName());
        e.setStatus(run.getStatus().name());
        e.setTotalRecords(run.getTotalRecords());
        e.setMatchedCount(run.getMatchedCount());
        e.setUnmatchedCount(run.getUnmatchedCount());
        e.setExceptionCount(run.getExceptionCount());
        e.setStartedAt(run.getStartedAt());
        e.setCompletedAt(run.getCompletedAt());
        e.setCreatedAt(run.getCreatedAt());
        return e;
    }

    private ReconciliationRun toDomain(ReconciliationRunEntity e) {
        ReconciliationRun run = new ReconciliationRun();
        run.setId(e.getId());
        run.setTenantId(e.getTenantId());
        run.setProvider(e.getProvider());
        run.setFileName(e.getFileName());
        run.setStatus(ReconciliationRun.RunStatus.valueOf(e.getStatus()));
        run.setTotalRecords(e.getTotalRecords());
        run.setMatchedCount(e.getMatchedCount());
        run.setUnmatchedCount(e.getUnmatchedCount());
        run.setExceptionCount(e.getExceptionCount());
        run.setStartedAt(e.getStartedAt());
        run.setCompletedAt(e.getCompletedAt());
        run.setCreatedAt(e.getCreatedAt());
        return run;
    }

    private SettlementRecordEntity toEntity(SettlementRecord r) {
        SettlementRecordEntity e = new SettlementRecordEntity();
        e.setId(r.getId());
        e.setReconciliationRunId(r.getReconciliationRunId());
        e.setTenantId(r.getTenantId());
        e.setProvider(r.getProvider());
        e.setExternalId(r.getExternalId());
        e.setPaymentReference(r.getPaymentReference());
        e.setAmount(r.getAmount());
        e.setCurrency(r.getCurrency());
        e.setFeeAmount(r.getFeeAmount());
        e.setNetAmount(r.getNetAmount());
        e.setSettledAt(r.getSettledAt());
        e.setMatchStatus(r.getMatchStatus());
        e.setMatchedPaymentId(r.getMatchedPaymentId());
        e.setMatchedJournalEntryId(r.getMatchedJournalEntryId());
        e.setRawData(r.getRawData());
        e.setCreatedAt(r.getCreatedAt());
        return e;
    }

    private SettlementRecord toDomain(SettlementRecordEntity e) {
        SettlementRecord r = new SettlementRecord();
        r.setId(e.getId());
        r.setReconciliationRunId(e.getReconciliationRunId());
        r.setTenantId(e.getTenantId());
        r.setProvider(e.getProvider());
        r.setExternalId(e.getExternalId());
        r.setPaymentReference(e.getPaymentReference());
        r.setAmount(e.getAmount());
        r.setCurrency(e.getCurrency());
        r.setFeeAmount(e.getFeeAmount());
        r.setNetAmount(e.getNetAmount());
        r.setSettledAt(e.getSettledAt());
        r.setMatchStatus(e.getMatchStatus());
        r.setMatchedPaymentId(e.getMatchedPaymentId());
        r.setMatchedJournalEntryId(e.getMatchedJournalEntryId());
        r.setRawData(e.getRawData());
        r.setCreatedAt(e.getCreatedAt());
        return r;
    }

    private ReconciliationExceptionEntity toEntity(ReconciliationException ex) {
        ReconciliationExceptionEntity e = new ReconciliationExceptionEntity();
        e.setId(ex.getId());
        e.setTenantId(ex.getTenantId());
        e.setReconciliationRunId(ex.getReconciliationRunId());
        e.setSettlementRecordId(ex.getSettlementRecordId());
        e.setExceptionType(ex.getExceptionType() != null ? ex.getExceptionType().name() : "UNKNOWN");
        e.setExpectedAmount(ex.getExpectedAmount());
        e.setActualAmount(ex.getActualAmount());
        e.setDescription(ex.getDescription());
        e.setStatus(ex.getStatus().name());
        e.setAssignedTo(ex.getAssignedTo());
        e.setResolvedAt(ex.getResolvedAt());
        e.setResolutionNotes(ex.getResolutionNotes());
        e.setCreatedAt(ex.getCreatedAt());
        return e;
    }

    private ReconciliationException toDomain(ReconciliationExceptionEntity e) {
        ReconciliationException ex = new ReconciliationException();
        ex.setId(e.getId());
        ex.setTenantId(e.getTenantId());
        ex.setReconciliationRunId(e.getReconciliationRunId());
        ex.setSettlementRecordId(e.getSettlementRecordId());
        ex.setExceptionType(MatchResult.ExceptionType.valueOf(e.getExceptionType()));
        ex.setExpectedAmount(e.getExpectedAmount());
        ex.setActualAmount(e.getActualAmount());
        ex.setDescription(e.getDescription());
        ex.setStatus(ReconciliationException.ExceptionStatus.valueOf(e.getStatus()));
        ex.setAssignedTo(e.getAssignedTo());
        ex.setResolvedAt(e.getResolvedAt());
        ex.setResolutionNotes(e.getResolutionNotes());
        ex.setCreatedAt(e.getCreatedAt());
        return ex;
    }

    // ---- Spring Data JPA repositories ----

    public interface JpaRunRepository extends JpaRepository<ReconciliationRunEntity, String> {
        List<ReconciliationRunEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, PageRequest page);
    }

    public interface JpaSettlementRecordRepository extends JpaRepository<SettlementRecordEntity, String> {
        List<SettlementRecordEntity> findByReconciliationRunId(String runId);
    }

    public interface JpaExceptionRepository extends JpaRepository<ReconciliationExceptionEntity, String> {
        List<ReconciliationExceptionEntity> findByReconciliationRunId(String runId);
        List<ReconciliationExceptionEntity> findByTenantIdAndStatusOrderByCreatedAtDesc(
                String tenantId, String status, PageRequest page);
    }
}
