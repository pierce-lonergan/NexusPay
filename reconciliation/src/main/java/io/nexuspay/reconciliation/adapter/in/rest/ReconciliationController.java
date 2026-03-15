package io.nexuspay.reconciliation.adapter.in.rest;

import io.nexuspay.reconciliation.application.port.out.ReconciliationRepository;
import io.nexuspay.reconciliation.application.service.ExceptionManagementService;
import io.nexuspay.reconciliation.application.service.ReconciliationOrchestrator;
import io.nexuspay.reconciliation.domain.ReconciliationException;
import io.nexuspay.reconciliation.domain.ReconciliationRun;
import io.nexuspay.reconciliation.domain.SettlementRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST API for reconciliation operations.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code POST /v1/reconciliation/runs} — trigger reconciliation (file upload)</li>
 *   <li>{@code GET /v1/reconciliation/runs} — list runs</li>
 *   <li>{@code GET /v1/reconciliation/runs/{id}} — run details</li>
 *   <li>{@code GET /v1/reconciliation/runs/{id}/records} — settlement records for a run</li>
 *   <li>{@code GET /v1/reconciliation/exceptions} — list open exceptions</li>
 *   <li>{@code POST /v1/reconciliation/exceptions/{id}/resolve} — resolve exception</li>
 *   <li>{@code POST /v1/reconciliation/exceptions/{id}/assign} — assign exception</li>
 * </ul>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@RestController
@RequestMapping("/v1/reconciliation")
public class ReconciliationController {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationController.class);

    private final ReconciliationOrchestrator orchestrator;
    private final ExceptionManagementService exceptionService;
    private final ReconciliationRepository repository;

    public ReconciliationController(ReconciliationOrchestrator orchestrator,
                                     ExceptionManagementService exceptionService,
                                     ReconciliationRepository repository) {
        this.orchestrator = orchestrator;
        this.exceptionService = exceptionService;
        this.repository = repository;
    }

    /**
     * Triggers a reconciliation run by uploading a settlement file.
     */
    @PostMapping("/runs")
    public ResponseEntity<ReconciliationRunResponse> createRun(
            @RequestParam("file") MultipartFile file,
            @RequestParam("provider") String provider,
            @RequestHeader("X-Tenant-Id") String tenantId) throws IOException {

        log.info("Reconciliation run requested: provider={}, file={}, tenant={}",
                provider, file.getOriginalFilename(), tenantId);

        ReconciliationRun run = orchestrator.runFromUpload(
                tenantId, provider, file.getOriginalFilename(), file.getInputStream());

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(run));
    }

    /**
     * Lists reconciliation runs for the current tenant.
     */
    @GetMapping("/runs")
    public ResponseEntity<List<ReconciliationRunResponse>> listRuns(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        List<ReconciliationRun> runs = repository.findRunsByTenant(tenantId, limit, offset);
        return ResponseEntity.ok(runs.stream().map(this::toResponse).toList());
    }

    /**
     * Gets details of a specific reconciliation run.
     */
    @GetMapping("/runs/{id}")
    public ResponseEntity<ReconciliationRunResponse> getRun(@PathVariable String id) {
        return repository.findRunById(id)
                .map(run -> ResponseEntity.ok(toResponse(run)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Lists settlement records for a reconciliation run.
     */
    @GetMapping("/runs/{id}/records")
    public ResponseEntity<List<SettlementRecordResponse>> getRunRecords(@PathVariable String id) {
        List<SettlementRecord> records = repository.findSettlementRecordsByRunId(id);
        return ResponseEntity.ok(records.stream().map(this::toResponse).toList());
    }

    /**
     * Lists open exceptions for the current tenant.
     */
    @GetMapping("/exceptions")
    public ResponseEntity<List<ExceptionResponse>> listExceptions(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        List<ReconciliationException> exceptions = exceptionService.listOpenExceptions(tenantId, limit, offset);
        return ResponseEntity.ok(exceptions.stream().map(this::toResponse).toList());
    }

    /**
     * Resolves an exception with resolution notes.
     */
    @PostMapping("/exceptions/{id}/resolve")
    public ResponseEntity<ExceptionResponse> resolveException(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {

        String notes = body.getOrDefault("notes", "");
        ReconciliationException ex = exceptionService.resolve(id, notes);
        return ResponseEntity.ok(toResponse(ex));
    }

    /**
     * Assigns an exception to a user for investigation.
     */
    @PostMapping("/exceptions/{id}/assign")
    public ResponseEntity<ExceptionResponse> assignException(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {

        String userId = body.get("user_id");
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        ReconciliationException ex = exceptionService.assign(id, userId);
        return ResponseEntity.ok(toResponse(ex));
    }

    // ---- Response DTOs ----

    private ReconciliationRunResponse toResponse(ReconciliationRun run) {
        return new ReconciliationRunResponse(
                run.getId(), run.getTenantId(), run.getProvider(), run.getFileName(),
                run.getStatus().name(), run.getTotalRecords(), run.getMatchedCount(),
                run.getUnmatchedCount(), run.getExceptionCount(),
                run.getStartedAt() != null ? run.getStartedAt().toString() : null,
                run.getCompletedAt() != null ? run.getCompletedAt().toString() : null,
                run.getCreatedAt().toString(),
                run.matchRate()
        );
    }

    private SettlementRecordResponse toResponse(SettlementRecord r) {
        return new SettlementRecordResponse(
                r.getId(), r.getProvider(), r.getExternalId(), r.getPaymentReference(),
                r.getAmount(), r.getCurrency(), r.getFeeAmount(), r.getNetAmount(),
                r.getSettledAt().toString(), r.getMatchStatus(),
                r.getMatchedPaymentId(), r.getMatchedJournalEntryId()
        );
    }

    private ExceptionResponse toResponse(ReconciliationException ex) {
        return new ExceptionResponse(
                ex.getId(), ex.getReconciliationRunId(), ex.getSettlementRecordId(),
                ex.getExceptionType() != null ? ex.getExceptionType().name() : null,
                ex.getExpectedAmount(), ex.getActualAmount(), ex.getDescription(),
                ex.getStatus().name(), ex.getAssignedTo(),
                ex.getResolvedAt() != null ? ex.getResolvedAt().toString() : null,
                ex.getResolutionNotes(), ex.getCreatedAt().toString()
        );
    }

    record ReconciliationRunResponse(
            String id, String tenantId, String provider, String fileName,
            String status, int totalRecords, int matchedCount,
            int unmatchedCount, int exceptionCount,
            String startedAt, String completedAt, String createdAt,
            double matchRate
    ) {}

    record SettlementRecordResponse(
            String id, String provider, String externalId, String paymentReference,
            long amount, String currency, long feeAmount, long netAmount,
            String settledAt, String matchStatus,
            String matchedPaymentId, String matchedJournalEntryId
    ) {}

    record ExceptionResponse(
            String id, String reconciliationRunId, String settlementRecordId,
            String exceptionType, Long expectedAmount, Long actualAmount,
            String description, String status, String assignedTo,
            String resolvedAt, String resolutionNotes, String createdAt
    ) {}
}
