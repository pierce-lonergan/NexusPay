package io.nexuspay.reconciliation.adapter.in.rest;

import io.nexuspay.common.tenant.CallerTenant;
import io.nexuspay.common.tenant.TenantOwnership;
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
import org.springframework.security.access.prepost.PreAuthorize;
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

    /**
     * SEC-27: hard upper bound for the settlement-records page. The by-run records endpoint was
     * previously unbounded — a single request could stream an entire run's lines (tens of thousands)
     * and exhaust memory. An oversized {@code limit} is CAPPED to this value rather than rejected, so
     * legitimate clients keep working while the worst case stays bounded.
     */
    private static final int MAX_RECORDS_LIMIT = 500;
    private static final int DEFAULT_RECORDS_LIMIT = 100;

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
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<ReconciliationRunResponse> createRun(
            @RequestParam("file") MultipartFile file,
            @RequestParam("provider") String provider) throws IOException {

        // SEC-27: tenant resolved from the authenticated principal, never from a client X-Tenant-Id
        // header. Ingesting a settlement file under a spoofed tenant would write another tenant's
        // reconciliation data into the victim's books — the most damaging IDOR in this controller.
        String tenantId = CallerTenant.require();

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
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<List<ReconciliationRunResponse>> listRuns(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        // SEC-27: list scoped to the authenticated principal's tenant, never a client-supplied header.
        String tenantId = CallerTenant.require();
        List<ReconciliationRun> runs = repository.findRunsByTenant(tenantId, limit, offset);
        return ResponseEntity.ok(runs.stream().map(this::toResponse).toList());
    }

    /**
     * Gets details of a specific reconciliation run.
     */
    @GetMapping("/runs/{id}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<ReconciliationRunResponse> getRun(@PathVariable String id) {
        // SEC-27: by-id read scoped to the caller's tenant — a foreign-tenant run 404s (no oracle).
        ReconciliationRun run = TenantOwnership.require(
                repository.findRunByIdAndTenantId(id, CallerTenant.require()), "Reconciliation run");
        return ResponseEntity.ok(toResponse(run));
    }

    /**
     * Lists settlement records for a reconciliation run.
     */
    @GetMapping("/runs/{id}/records")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<List<SettlementRecordResponse>> getRunRecords(
            @PathVariable String id,
            @RequestParam(defaultValue = "" + DEFAULT_RECORDS_LIMIT) int limit,
            @RequestParam(defaultValue = "0") int offset) {

        String tenantId = CallerTenant.require();

        // SEC-27: assert the run belongs to the caller BEFORE returning any of its lines. A foreign
        // run id 404s via the tenant-scoped finder (no existence oracle). Without this, scoping only
        // the records query would silently return an empty 200 for a foreign run — leaking nothing,
        // but also masking the not-found contract the rest of the API uses.
        TenantOwnership.require(
                repository.findRunByIdAndTenantId(id, tenantId), "Reconciliation run");

        // SEC-27: clamp pagination — this endpoint was unbounded. Cap an oversized limit, floor a
        // non-positive one to the default, and never allow a negative offset.
        int clampedLimit = clampLimit(limit);
        int clampedOffset = Math.max(offset, 0);

        List<SettlementRecord> records = repository.findSettlementRecordsByRunIdAndTenantId(
                id, tenantId, clampedLimit, clampedOffset);
        return ResponseEntity.ok(records.stream().map(this::toResponse).toList());
    }

    /**
     * Lists open exceptions for the current tenant.
     */
    @GetMapping("/exceptions")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<List<ExceptionResponse>> listExceptions(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        // SEC-27: list scoped to the authenticated principal's tenant, never a client-supplied header.
        String tenantId = CallerTenant.require();
        List<ReconciliationException> exceptions = exceptionService.listOpenExceptions(tenantId, limit, offset);
        return ResponseEntity.ok(exceptions.stream().map(this::toResponse).toList());
    }

    /**
     * Resolves an exception with resolution notes.
     */
    @PostMapping("/exceptions/{id}/resolve")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<ExceptionResponse> resolveException(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {

        String notes = body.getOrDefault("notes", "");
        // SEC-27: mutation scoped to the caller's tenant — a foreign exception id 404s (no oracle).
        ReconciliationException ex = exceptionService.resolve(id, CallerTenant.require(), notes);
        return ResponseEntity.ok(toResponse(ex));
    }

    /**
     * Assigns an exception to a user for investigation.
     */
    @PostMapping("/exceptions/{id}/assign")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<ExceptionResponse> assignException(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {

        String userId = body.get("user_id");
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        // SEC-27: mutation scoped to the caller's tenant — a foreign exception id 404s (no oracle).
        ReconciliationException ex = exceptionService.assign(id, CallerTenant.require(), userId);
        return ResponseEntity.ok(toResponse(ex));
    }

    /**
     * Writes off an exception (accepted loss / immaterial discrepancy).
     */
    @PostMapping("/exceptions/{id}/write-off")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<ExceptionResponse> writeOffException(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {

        String notes = body.getOrDefault("notes", "");
        // SEC-27: mutation scoped to the caller's tenant — a foreign exception id 404s (no oracle).
        ReconciliationException ex = exceptionService.writeOff(id, CallerTenant.require(), notes);
        return ResponseEntity.ok(toResponse(ex));
    }

    /**
     * SEC-27: caps an oversized records-page limit to {@link #MAX_RECORDS_LIMIT} and floors a
     * non-positive one to {@link #DEFAULT_RECORDS_LIMIT}, keeping the worst-case page bounded.
     */
    private static int clampLimit(int requested) {
        if (requested <= 0) {
            return DEFAULT_RECORDS_LIMIT;
        }
        return Math.min(requested, MAX_RECORDS_LIMIT);
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
