package io.nexuspay.iam.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Maker-checker approval request.
 * Created when an operation requires admin approval (e.g., refund above threshold).
 */
public class PendingApproval {

    private final String id;
    private final String action;
    private final String resourceType;
    private final String resourceId;
    private final Map<String, Object> payload;
    private String status;
    private final String requestedBy;
    private String reviewedBy;
    private final String tenantId;
    private final Instant createdAt;
    private Instant reviewedAt;

    public PendingApproval(String id, String action, String resourceType, String resourceId,
                            Map<String, Object> payload, String status, String requestedBy,
                            String reviewedBy, String tenantId, Instant createdAt, Instant reviewedAt) {
        this.id = id;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.payload = payload;
        this.status = status;
        this.requestedBy = requestedBy;
        this.reviewedBy = reviewedBy;
        this.tenantId = tenantId;
        this.createdAt = createdAt;
        this.reviewedAt = reviewedAt;
    }

    public void approve(String reviewerId) {
        this.status = "APPROVED";
        this.reviewedBy = reviewerId;
        this.reviewedAt = Instant.now();
    }

    public void reject(String reviewerId) {
        this.status = "REJECTED";
        this.reviewedBy = reviewerId;
        this.reviewedAt = Instant.now();
    }

    public boolean isPending() { return "PENDING".equals(status); }
    public boolean isApproved() { return "APPROVED".equals(status); }
    public boolean isRejected() { return "REJECTED".equals(status); }

    public String getId() { return id; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public Map<String, Object> getPayload() { return payload; }
    public String getStatus() { return status; }
    public String getRequestedBy() { return requestedBy; }
    public String getReviewedBy() { return reviewedBy; }
    public String getTenantId() { return tenantId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReviewedAt() { return reviewedAt; }
}
