package io.nexuspay.dispute.adapter.in.rest;

import io.nexuspay.common.tenant.CallerTenant;
import io.nexuspay.dispute.application.service.DisputeLifecycleService;
import io.nexuspay.dispute.domain.*;
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
 * REST API for dispute management.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET  /v1/disputes}               — list disputes (paginated, filterable)</li>
 *   <li>{@code GET  /v1/disputes/{id}}           — dispute details</li>
 *   <li>{@code POST /v1/disputes/{id}/evidence}  — upload evidence file</li>
 *   <li>{@code POST /v1/disputes/{id}/submit}    — submit evidence to network</li>
 *   <li>{@code GET  /v1/disputes/{id}/events}    — dispute event timeline</li>
 * </ul>
 *
 * <h3>Tenant isolation (SEC-27)</h3>
 * <p>Every endpoint derives the tenant from the authenticated principal via
 * {@link CallerTenant#require()} — NEVER from a client {@code X-Tenant-Id} header (a header, if sent,
 * is ignored). By-id reads and the state-changing submit/upload operations resolve the dispute
 * through the tenant-scoped service finders, so a foreign-tenant id 404s with no existence oracle, and
 * the list endpoint enumerates only the caller's own disputes. All endpoints sit behind the global
 * {@code SecurityConfig.anyRequest().authenticated()} gate (mirrors the SEC-26 SubscriptionController,
 * which also relies on the global auth gate rather than a {@code @PreAuthorize} on each method).
 * The inbound dispute WEBHOOK is a separate, server-authoritative, SEC-2-hardened path.</p>
 *
 * @since 0.2.4 (Sprint 2.4)
 */
@RestController
@RequestMapping("/v1/disputes")
public class DisputeController {

    private static final Logger log = LoggerFactory.getLogger(DisputeController.class);

    private final DisputeLifecycleService lifecycleService;

    public DisputeController(DisputeLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    /**
     * Lists disputes for the current tenant, optionally filtered by status.
     */
    @GetMapping
    public ResponseEntity<List<DisputeResponse>> listDisputes(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        // SEC-27: list scoped to the AUTHENTICATED principal's tenant, never a client X-Tenant-Id
        // header — a caller can only ever enumerate their own tenant's disputes.
        List<Dispute> disputes = lifecycleService.listByTenant(CallerTenant.require(), limit, offset);
        return ResponseEntity.ok(disputes.stream().map(this::toResponse).toList());
    }

    /**
     * Gets details of a specific dispute.
     */
    @GetMapping("/{id}")
    public ResponseEntity<DisputeResponse> getDispute(@PathVariable String id) {
        // SEC-27: by-id read scoped to the caller's tenant — a foreign-tenant id 404s (no oracle).
        return lifecycleService.findById(id, CallerTenant.require())
                .map(d -> ResponseEntity.ok(toResponse(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Uploads an evidence file for a dispute.
     */
    @PostMapping("/{id}/evidence")
    public ResponseEntity<EvidenceResponse> uploadEvidence(
            @PathVariable String id,
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") String evidenceType,
            @RequestParam(value = "description", required = false) String description) throws IOException {

        DisputeEvidenceType type;
        try {
            type = DisputeEvidenceType.valueOf(evidenceType.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        // SEC-27: tenant resolved from the authenticated principal, never from a client X-Tenant-Id
        // header. The service scopes the dispute lookup to this tenant (404 on a foreign id) so a
        // tenant-A caller cannot attach evidence to a tenant-B dispute.
        DisputeEvidence evidence = lifecycleService.uploadEvidence(
                id, CallerTenant.require(), type, file.getOriginalFilename(),
                file.getInputStream(), file.getContentType(), description);

        return ResponseEntity.status(HttpStatus.CREATED).body(toEvidenceResponse(evidence));
    }

    /**
     * Submits collected evidence to the card network.
     */
    @PostMapping("/{id}/submit")
    public ResponseEntity<DisputeResponse> submitEvidence(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {

        String actor = body != null ? body.getOrDefault("actor", "api") : "api";
        // SEC-27: mutation scoped to the caller's tenant — a tenant-A caller cannot submit evidence
        // on a tenant-B dispute (404 on a foreign id, no oracle). Tenant from the principal, not a header.
        Dispute dispute = lifecycleService.submitEvidence(id, CallerTenant.require(), actor);
        return ResponseEntity.ok(toResponse(dispute));
    }

    /**
     * Returns the event timeline for a dispute.
     */
    @GetMapping("/{id}/events")
    public ResponseEntity<List<EventResponse>> getEvents(@PathVariable String id) {
        // SEC-27: event timeline scoped to the caller's tenant — a foreign-tenant id yields an empty
        // timeline (no oracle). Tenant from the authenticated principal, never a client header.
        List<DisputeEvent> events = lifecycleService.getTimeline(id, CallerTenant.require());
        return ResponseEntity.ok(events.stream().map(this::toEventResponse).toList());
    }

    // ---- Response DTOs ----

    private DisputeResponse toResponse(Dispute d) {
        return new DisputeResponse(
                d.getId(), d.getTenantId(), d.getPaymentId(), d.getExternalDisputeId(),
                d.getReasonCode(), d.getReasonDescription(),
                d.getAmount(), d.getCurrency(), d.getStatus().name(),
                d.getNetwork(), d.getOutcome(),
                d.getEvidenceDueDate() != null ? d.getEvidenceDueDate().toString() : null,
                d.getEvidenceSubmittedAt() != null ? d.getEvidenceSubmittedAt().toString() : null,
                d.getResolvedAt() != null ? d.getResolvedAt().toString() : null,
                d.getCreatedAt().toString(), d.getUpdatedAt().toString()
        );
    }

    private EvidenceResponse toEvidenceResponse(DisputeEvidence e) {
        return new EvidenceResponse(
                e.getId(), e.getDisputeId(), e.getEvidenceType().name(),
                e.getFileKey(), e.getFileName(), e.getFileSize(),
                e.getDescription(), e.getUploadedAt().toString()
        );
    }

    private EventResponse toEventResponse(DisputeEvent e) {
        return new EventResponse(
                e.getId(), e.getDisputeId(), e.getEventType(),
                e.getOldStatus() != null ? e.getOldStatus().name() : null,
                e.getNewStatus() != null ? e.getNewStatus().name() : null,
                e.getActor(), e.getDetails(), e.getCreatedAt().toString()
        );
    }

    record DisputeResponse(
            String id, String tenantId, String paymentId, String externalDisputeId,
            String reasonCode, String reasonDescription,
            long amount, String currency, String status,
            String network, String outcome,
            String evidenceDueDate, String evidenceSubmittedAt,
            String resolvedAt, String createdAt, String updatedAt
    ) {}

    record EvidenceResponse(
            String id, String disputeId, String evidenceType,
            String fileKey, String fileName, Long fileSize,
            String description, String uploadedAt
    ) {}

    record EventResponse(
            String id, String disputeId, String eventType,
            String oldStatus, String newStatus,
            String actor, Map<String, Object> details, String createdAt
    ) {}
}
