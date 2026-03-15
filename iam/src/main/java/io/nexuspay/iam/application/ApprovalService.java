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
        var entity = approvalRepository.findById(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + approvalId));

        if (!"PENDING".equals(entity.getStatus())) {
            throw new IllegalStateException("Approval is not pending: " + entity.getStatus());
        }

        if (entity.getRequestedBy().equals(reviewerId)) {
            throw AuthorizationException.forbidden("Cannot approve own request");
        }

        entity.setStatus("APPROVED");
        entity.setReviewedBy(reviewerId);
        entity.setReviewedAt(Instant.now());
        approvalRepository.save(entity);

        auditService.logAction(reviewerId, "approval_approved", entity.getResourceType(),
                approvalId, Map.of("action", entity.getAction()), null, tenantId);

        log.info("Approved: id={}, by={}", approvalId, reviewerId);
        return toDomain(entity);
    }

    @Transactional
    public PendingApproval reject(String approvalId, String reviewerId, String tenantId) {
        var entity = approvalRepository.findById(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + approvalId));

        if (!"PENDING".equals(entity.getStatus())) {
            throw new IllegalStateException("Approval is not pending: " + entity.getStatus());
        }

        entity.setStatus("REJECTED");
        entity.setReviewedBy(reviewerId);
        entity.setReviewedAt(Instant.now());
        approvalRepository.save(entity);

        auditService.logAction(reviewerId, "approval_rejected", entity.getResourceType(),
                approvalId, Map.of("action", entity.getAction()), null, tenantId);

        log.info("Rejected: id={}, by={}", approvalId, reviewerId);
        return toDomain(entity);
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
