package io.nexuspay.iam.application;

import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.iam.adapter.out.persistence.JpaPendingApprovalRepository;
import io.nexuspay.iam.adapter.out.persistence.PendingApprovalEntity;
import io.nexuspay.iam.domain.PendingApproval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maker-checker approval workflow.
 * When a refund exceeds the threshold, an approval request is created.
 * An admin must approve/reject before the action executes.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final JpaPendingApprovalRepository approvalRepository;
    private final AuditService auditService;

    public ApprovalService(JpaPendingApprovalRepository approvalRepository,
                            AuditService auditService) {
        this.approvalRepository = approvalRepository;
        this.auditService = auditService;
    }

    @Transactional
    public PendingApproval createApproval(String action, String resourceType, String resourceId,
                                           Map<String, Object> payload, String requestedBy,
                                           String tenantId) {
        var entity = new PendingApprovalEntity(
                PrefixedId.approval(),
                action, resourceType, resourceId,
                payload, "PENDING", requestedBy, null,
                tenantId, Instant.now(), null
        );
        approvalRepository.save(entity);

        auditService.logAction(requestedBy, "approval_requested", resourceType,
                entity.getId(), Map.of("action", action, "resource_id", resourceId != null ? resourceId : ""),
                null, tenantId);

        log.info("Created approval request: id={}, action={}, requestedBy={}",
                entity.getId(), action, requestedBy);
        return toDomain(entity);
    }

    @Transactional
    public PendingApproval approve(String approvalId, String reviewerId, String tenantId) {
        var entity = loadForReview(approvalId, tenantId);

        if (entity.getRequestedBy().equals(reviewerId)) {
            throw AuthorizationException.forbidden("Cannot approve own request");
        }

        // Atomic claim: only the request that flips PENDING->APPROVED proceeds, so
        // concurrent approve calls cannot both execute the downstream action (e.g.
        // a refund) — the maker-checker control is execute-once (B-009).
        int claimed = approvalRepository.transitionFromPending(
                approvalId, tenantId, "APPROVED", reviewerId, Instant.now());
        if (claimed != 1) {
            throw new IllegalStateException("Approval is not pending (already processed): " + approvalId);
        }

        auditService.logAction(reviewerId, "approval_approved", entity.getResourceType(),
                approvalId, Map.of("action", entity.getAction()), null, tenantId);

        log.info("Approved: id={}, by={}", approvalId, reviewerId);
        return findById(approvalId).orElseThrow();
    }

    @Transactional
    public PendingApproval reject(String approvalId, String reviewerId, String tenantId) {
        var entity = loadForReview(approvalId, tenantId);

        int claimed = approvalRepository.transitionFromPending(
                approvalId, tenantId, "REJECTED", reviewerId, Instant.now());
        if (claimed != 1) {
            throw new IllegalStateException("Approval is not pending (already processed): " + approvalId);
        }

        auditService.logAction(reviewerId, "approval_rejected", entity.getResourceType(),
                approvalId, Map.of("action", entity.getAction()), null, tenantId);

        log.info("Rejected: id={}, by={}", approvalId, reviewerId);
        return findById(approvalId).orElseThrow();
    }

    /**
     * Loads an approval and enforces tenant ownership. The by-id lookup is not
     * tenant-scoped at the DB layer (RLS is not yet enforced at runtime — B-002),
     * so an explicit tenant check prevents a tenant from approving/rejecting
     * another tenant's request (and moving its money).
     */
    private PendingApprovalEntity loadForReview(String approvalId, String tenantId) {
        var entity = approvalRepository.findById(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + approvalId));
        if (!entity.getTenantId().equals(tenantId)) {
            // Don't reveal cross-tenant existence — treat as not found.
            throw new IllegalArgumentException("Approval not found: " + approvalId);
        }
        if (!"PENDING".equals(entity.getStatus())) {
            throw new IllegalStateException("Approval is not pending: " + entity.getStatus());
        }
        return entity;
    }

    @Transactional(readOnly = true)
    public List<PendingApproval> listPending(String tenantId) {
        return approvalRepository.findAllByStatusAndTenantId("PENDING", tenantId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<PendingApproval> findById(String id) {
        return approvalRepository.findById(id).map(this::toDomain);
    }

    // ----------------------------------------------------------------------------
    // B-022: stuck-APPROVED refund reconciler support.
    //
    // These methods are the iam-owned seam the gateway-api RefundReconciler drives:
    // discovery runs SYSTEM (cross-tenant) under the scheduler's @SystemTransactional
    // pin; the per-item re-check / mark / failure writes run inside the reconciler's
    // tenantWork.callInTenant(...) tenant-bound REQUIRES_NEW tx, so RLS WITH CHECK on
    // pending_approvals scopes each write to the row's own tenant.
    // ----------------------------------------------------------------------------

    /**
     * SYSTEM discovery: the stuck "APPROVED + action=refund + executed_at NULL + attempts<max +
     * backoff-due" set, across ALL tenants, oldest first. MUST be called from a
     * {@code @SystemTransactional} (BYPASSRLS) context so RLS does not hide other tenants' rows.
     */
    @Transactional(readOnly = true)
    public List<PendingApproval> findStuckApprovedRefunds(Instant now, int maxAttempts, int batch) {
        return approvalRepository
                .findApprovedUnexecutedRefunds(now, maxAttempts, PageRequest.of(0, batch))
                .stream().map(this::toDomain).toList();
    }

    /**
     * SYSTEM operator-signal query: APPROVED refunds that exhausted reconcile attempts and are still
     * unexecuted (need a human). Cross-tenant; call under {@code @SystemTransactional}.
     */
    @Transactional(readOnly = true)
    public List<PendingApproval> findExhaustedRefunds(int maxAttempts) {
        return approvalRepository.findExhaustedRefunds(maxAttempts).stream()
                .map(this::toDomain).toList();
    }

    /**
     * Re-loads the row FOR UPDATE inside the (caller-provided) tenant-bound tx and returns it as a
     * domain object ONLY IF it is still unexecuted — the secondary intra-cycle guard. Returns empty
     * if another writer/the original already set {@code executed_at} (skip the redundant re-drive) or
     * the row vanished. Must be invoked inside {@code tenantWork.callInTenant(tenantId, ...)}.
     */
    @Transactional
    public Optional<PendingApproval> reloadUnexecutedForUpdate(String approvalId) {
        return approvalRepository.findByIdForUpdate(approvalId)
                .filter(e -> e.getExecutedAt() == null)
                .map(this::toDomain);
    }

    /**
     * Marks a refund EXECUTED (executed_at = now), conditional on executed_at IS NULL and bound to
     * the row's tenant. Returns {@code true} iff THIS call flipped it (so the caller can tell a real
     * mark from a no-op when a concurrent writer won). Call inside the row's tenant via callInTenant.
     */
    @Transactional
    public boolean markRefundExecuted(String approvalId, String tenantId) {
        int updated = approvalRepository.markRefundExecuted(approvalId, tenantId, Instant.now());
        if (updated == 1) {
            log.info("Refund marked executed: approval={}, tenant={}", approvalId, tenantId);
        }
        return updated == 1;
    }

    /**
     * Records a reconcile FAILURE: increments reconcile_attempts, sets the backoff gate
     * ({@code nextReconcileAt}) and last error, and LEAVES executed_at NULL so the row re-drives next
     * cycle (never a terminal/stranding state). Bound to the row's tenant; call inside callInTenant.
     * The backoff value is computed by the caller (the reconciler owns the policy, like
     * DeadLetterReprocessor).
     */
    @Transactional
    public void recordReconcileFailure(String approvalId, String tenantId,
                                       Instant nextReconcileAt, String error) {
        approvalRepository.recordReconcileFailure(approvalId, tenantId, nextReconcileAt, truncateError(error));
    }

    /**
     * Records a BENIGN PSP {@code pending} re-check (B-022 FIX 2): sets the next re-check gate WITHOUT
     * incrementing reconcile_attempts and WITHOUT treating it as a failure — the PSP accepted the
     * refund and is settling it async (it dedups the key, so money moves once). Leaves executed_at NULL
     * so a later cycle re-drives the SAME key and marks it once the PSP reports {@code succeeded}.
     * Because attempts is untouched, a perpetually-pending refund never reaches the exhausted tail and
     * so never false-pages the operator-signal sweep. Bound to the row's tenant; call inside callInTenant.
     */
    @Transactional
    public void recordPendingRecheck(String approvalId, String tenantId,
                                     Instant nextReconcileAt, String note) {
        approvalRepository.recordPendingRecheck(approvalId, tenantId, nextReconcileAt, truncateError(note));
    }

    /** Keeps last_reconcile_error bounded so a verbose stack/message can't bloat the row. */
    private static String truncateError(String error) {
        if (error == null) return null;
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    private PendingApproval toDomain(PendingApprovalEntity entity) {
        return new PendingApproval(
                entity.getId(), entity.getAction(), entity.getResourceType(),
                entity.getResourceId(), entity.getPayload(), entity.getStatus(),
                entity.getRequestedBy(), entity.getReviewedBy(), entity.getTenantId(),
                entity.getCreatedAt(), entity.getReviewedAt(),
                entity.getExecutedAt(), entity.getReconcileAttempts(),
                entity.getNextReconcileAt(), entity.getLastReconcileError()
        );
    }
}
