package io.nexuspay.fraud.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A fraud detection rule with condition DSL, action, priority, and A/B testing support.
 *
 * <p>Rules are tenant-scoped and versioned. Only enabled rules participate in evaluation.
 * A/B test groups allow splitting traffic to compare rule effectiveness.</p>
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public class FraudRule {

    private UUID id;
    private String tenantId;
    private String ruleName;
    private RuleType ruleType;
    private RuleCondition condition;
    private RuleAction action;
    private int scoreAdjustment;
    private int priority;
    private int version;
    private String abTestGroup;    // null = always active, "A" or "B" for A/B testing
    private Double abTestTraffic;  // percentage of traffic for this group (0.0-1.0)
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;

    public FraudRule() {}

    public FraudRule(UUID id, String tenantId, String ruleName, RuleType ruleType,
                     RuleCondition condition, RuleAction action, int scoreAdjustment,
                     int priority, int version, boolean enabled, String createdBy) {
        this.id = id;
        this.tenantId = tenantId;
        this.ruleName = ruleName;
        this.ruleType = ruleType;
        this.condition = condition;
        this.action = action;
        this.scoreAdjustment = scoreAdjustment;
        this.priority = priority;
        this.version = version;
        this.enabled = enabled;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Determines if this rule should participate in evaluation for a given A/B test hash.
     * Rules with no A/B group are always active.
     */
    public boolean shouldEvaluate(double trafficHash) {
        if (abTestGroup == null) return true;
        if (abTestTraffic == null) return true;
        return trafficHash <= abTestTraffic;
    }

    // --- Getters & Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public RuleType getRuleType() { return ruleType; }
    public void setRuleType(RuleType ruleType) { this.ruleType = ruleType; }

    public RuleCondition getCondition() { return condition; }
    public void setCondition(RuleCondition condition) { this.condition = condition; }

    public RuleAction getAction() { return action; }
    public void setAction(RuleAction action) { this.action = action; }

    public int getScoreAdjustment() { return scoreAdjustment; }
    public void setScoreAdjustment(int scoreAdjustment) { this.scoreAdjustment = scoreAdjustment; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getAbTestGroup() { return abTestGroup; }
    public void setAbTestGroup(String abTestGroup) { this.abTestGroup = abTestGroup; }

    public Double getAbTestTraffic() { return abTestTraffic; }
    public void setAbTestTraffic(Double abTestTraffic) { this.abTestTraffic = abTestTraffic; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
