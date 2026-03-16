package io.nexuspay.payment.adapter.out.persistence.routing;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for routing decisions (audit trail).
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Entity
@Table(name = "routing_decisions")
public class RoutingDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "payment_id", nullable = false)
    private String paymentId;

    @Column(name = "strategy_used", nullable = false)
    private String strategyUsed;

    @Column(name = "config_id", nullable = false)
    private UUID configId;

    @Column(name = "selected_psp", nullable = false)
    private String selectedPsp;

    @Column(name = "candidate_scores", columnDefinition = "JSONB", nullable = false)
    private String candidateScores;

    @Column(name = "cascade_depth", nullable = false)
    private int cascadeDepth;

    @Column(name = "cascade_psps", columnDefinition = "JSONB")
    private String cascadePsps;

    @Column(name = "final_psp")
    private String finalPsp;

    @Column(name = "ab_test_id")
    private UUID abTestId;

    @Column(name = "ab_test_group")
    private String abTestGroup;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "decision_latency_ms", nullable = false)
    private int decisionLatencyMs;

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public String getStrategyUsed() { return strategyUsed; }
    public void setStrategyUsed(String strategyUsed) { this.strategyUsed = strategyUsed; }
    public UUID getConfigId() { return configId; }
    public void setConfigId(UUID configId) { this.configId = configId; }
    public String getSelectedPsp() { return selectedPsp; }
    public void setSelectedPsp(String selectedPsp) { this.selectedPsp = selectedPsp; }
    public String getCandidateScores() { return candidateScores; }
    public void setCandidateScores(String candidateScores) { this.candidateScores = candidateScores; }
    public int getCascadeDepth() { return cascadeDepth; }
    public void setCascadeDepth(int cascadeDepth) { this.cascadeDepth = cascadeDepth; }
    public String getCascadePsps() { return cascadePsps; }
    public void setCascadePsps(String cascadePsps) { this.cascadePsps = cascadePsps; }
    public String getFinalPsp() { return finalPsp; }
    public void setFinalPsp(String finalPsp) { this.finalPsp = finalPsp; }
    public UUID getAbTestId() { return abTestId; }
    public void setAbTestId(UUID abTestId) { this.abTestId = abTestId; }
    public String getAbTestGroup() { return abTestGroup; }
    public void setAbTestGroup(String abTestGroup) { this.abTestGroup = abTestGroup; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public int getDecisionLatencyMs() { return decisionLatencyMs; }
    public void setDecisionLatencyMs(int decisionLatencyMs) { this.decisionLatencyMs = decisionLatencyMs; }
}
