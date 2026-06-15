package io.nexuspay.fraud.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The result of a fraud risk assessment for a payment.
 *
 * <p>Combines native rule scores with external FRM provider scores
 * into an aggregated decision of ALLOW, REVIEW, or BLOCK.</p>
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public class RiskAssessment {

    private UUID id;
    private String tenantId;
    private String paymentId;
    private int nativeScore;          // 0-100 from native rules
    private Integer frmScore;         // 0-100 from external FRM (null if unavailable)
    private String frmProvider;       // SIFT, SIGNIFYD, NATIVE_ONLY
    private int aggregatedScore;      // weighted combination
    private RiskDecision decision;
    private List<String> triggeredRuleIds = new ArrayList<>();
    private List<RiskSignal> riskSignals = new ArrayList<>();
    private String reviewStatus;      // null, PENDING_REVIEW, APPROVED, REJECTED
    private String reviewedBy;
    private Instant reviewedAt;
    private Instant assessedAt;
    private int latencyMs;
    // B-029-hardening: keyed HMAC-SHA256 (hex) of the canonical request tuple (amount/currency/
    // customer/cardToken). Set by the service right before persistence; nullable so legacy/bypass
    // paths leave it null (matching the nullable DB column and the "legacy NULL -> return prior"
    // dedup-hit branch). Never reversible, never a raw PAN.
    private String requestFingerprint;

    public RiskAssessment() {
        this.id = UUID.randomUUID();
        this.assessedAt = Instant.now();
    }

    public static RiskAssessment create(String tenantId, String paymentId) {
        var assessment = new RiskAssessment();
        assessment.tenantId = tenantId;
        assessment.paymentId = paymentId;
        return assessment;
    }

    public void addTriggeredRule(UUID ruleId) {
        triggeredRuleIds.add(ruleId.toString());
    }

    public void addRiskSignal(RiskSignal signal) {
        riskSignals.add(signal);
    }

    public void applyDecision(int nativeScore, Integer frmScore, String frmProvider,
                               int aggregatedScore, RiskDecision decision) {
        this.nativeScore = nativeScore;
        this.frmScore = frmScore;
        this.frmProvider = frmProvider;
        this.aggregatedScore = aggregatedScore;
        this.decision = decision;
        if (decision == RiskDecision.REVIEW) {
            this.reviewStatus = "PENDING_REVIEW";
        }
    }

    public void approve(String reviewedBy) {
        this.reviewStatus = "APPROVED";
        this.reviewedBy = reviewedBy;
        this.reviewedAt = Instant.now();
    }

    public void reject(String reviewedBy) {
        this.reviewStatus = "REJECTED";
        this.reviewedBy = reviewedBy;
        this.reviewedAt = Instant.now();
    }

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

    public RiskDecision getDecision() { return decision; }
    public void setDecision(RiskDecision decision) { this.decision = decision; }

    public List<String> getTriggeredRuleIds() { return triggeredRuleIds; }
    public void setTriggeredRuleIds(List<String> triggeredRuleIds) { this.triggeredRuleIds = triggeredRuleIds; }

    public List<RiskSignal> getRiskSignals() { return riskSignals; }
    public void setRiskSignals(List<RiskSignal> riskSignals) { this.riskSignals = riskSignals; }

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

    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
}
