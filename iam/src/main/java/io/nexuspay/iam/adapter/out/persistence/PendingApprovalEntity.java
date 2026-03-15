package io.nexuspay.iam.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "pending_approvals")
public class PendingApprovalEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "requested_by", nullable = false, length = 128)
    private String requestedBy;

    @Column(name = "reviewed_by", length = 128)
    private String reviewedBy;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected PendingApprovalEntity() {}

    public PendingApprovalEntity(String id, String action, String resourceType, String resourceId,
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

    public void setStatus(String status) { this.status = status; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
}
