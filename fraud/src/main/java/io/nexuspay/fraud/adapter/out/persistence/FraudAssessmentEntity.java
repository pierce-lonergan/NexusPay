package io.nexuspay.fraud.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for fraud_assessments table.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Entity
@Table(name = "fraud_assessments")
public class FraudAssessmentEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "payment_id", nullable = false)
    private String paymentId;

    @Column(name = "native_score", nullable = false)
    private int nativeScore;

    @Column(name = "frm_score")
    private Integer frmScore;

    @Column(name = "frm_provider")
    private String frmProvider;

    @Column(name = "aggregated_score", nullable = false)
    private int aggregatedScore;

    @Column(name = "decision", nullable = false)
    private String decision;

    @Column(name = "triggered_rules", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String triggeredRules;

    @Column(name = "risk_signals", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String riskSignals;

    @Column(name = "review_status")
    private String reviewStatus;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "assessed_at", nullable = false)
    private Instant assessedAt;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    // --- Getters & Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public int getNativeScore() { return nativeScore; }
    public void setNativeScore(int nativeScore) { this.nativeScore = nativeScore; }
    public Integer getFrmScore() { return frmScore; }
    public void setFrmScore(Integer frmScore) { this.frmScore = frmScore; }
    public String getFrmProvider() { return frmProvider; }
    public void setFrmProvider(String frmProvider) { this.frmProvider = frmProvider; }
    public int getAggregatedScore() { return aggregatedScore; }
    public void setAggregatedScore(int aggregatedScore) { this.aggregatedScore = aggregatedScore; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getTriggeredRules() { return triggeredRules; }
    public void setTriggeredRules(String triggeredRules) { this.triggeredRules = triggeredRules; }
    public String getRiskSignals() { return riskSignals; }
    public void setRiskSignals(String riskSignals) { this.riskSignals = riskSignals; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public Instant getAssessedAt() { return assessedAt; }
    public void setAssessedAt(Instant assessedAt) { this.assessedAt = assessedAt; }
    public int getLatencyMs() { return latencyMs; }
    public void setLatencyMs(int latencyMs) { this.latencyMs = latencyMs; }
}
