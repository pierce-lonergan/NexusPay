package io.nexuspay.payment.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Capture-hold record (B-024/B-027): a payment placed on hold pending fraud review.
 * Maps to {@code payment_capture_hold} (V4010). RLS-isolated by {@code tenant_id}.
 */
@Entity
@Table(name = "payment_capture_hold")
public class CaptureHoldEntity {

    @Id
    @Column(name = "payment_id")
    private String paymentId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "fraud_assessment_id")
    private String fraudAssessmentId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "released_by")
    private String releasedBy;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected CaptureHoldEntity() {
    }

    public CaptureHoldEntity(String paymentId, String tenantId, String fraudAssessmentId,
                             String status, Instant createdAt) {
        this.paymentId = paymentId;
        this.tenantId = tenantId;
        this.fraudAssessmentId = fraudAssessmentId;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getPaymentId() { return paymentId; }
    public String getTenantId() { return tenantId; }
    public String getFraudAssessmentId() { return fraudAssessmentId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getReleasedBy() { return releasedBy; }
    public void setReleasedBy(String releasedBy) { this.releasedBy = releasedBy; }
    public Instant getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Instant releasedAt) { this.releasedAt = releasedAt; }
}
