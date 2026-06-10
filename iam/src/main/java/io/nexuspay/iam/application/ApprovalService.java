package io.nexuspay.iam.application;

import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.iam.adapter.out.persistence.JpaPendingApprovalRepository;
import io.nexuspay.iam.adapter.out.persistence.PendingApprovalEntity;
import io.nexuspay.iam.domain.PendingApproval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private PendingApproval toDomain(PendingApprovalEntity entity) {
        return new PendingApproval(
                entity.getId(), entity.getAction(), entity.getResourceType(),
                entity.getResourceId(), entity.getPayload(), entity.getStatus(),
                entity.getRequestedBy(), entity.getReviewedBy(), entity.getTenantId(),
                entity.getCreatedAt(), entity.getReviewedAt()
        );
    }
}
