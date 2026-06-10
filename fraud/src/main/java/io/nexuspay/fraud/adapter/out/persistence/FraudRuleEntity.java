package io.nexuspay.fraud.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for fraud_rules table.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Entity
@Table(name = "fraud_rules")
public class FraudRuleEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(name = "rule_type", nullable = false)
    private String ruleType;

    @Column(name = "condition_dsl", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String conditionDsl;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "score_adjustment")
    private int scoreAdjustment;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "ab_test_group")
    private String abTestGroup;

    @Column(name = "ab_test_traffic")
    private BigDecimal abTestTraffic;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    // --- Getters & Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }

    public String getConditionDsl() { return conditionDsl; }
    public void setConditionDsl(String conditionDsl) { this.conditionDsl = conditionDsl; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public int getScoreAdjustment() { return scoreAdjustment; }
    public void setScoreAdjustment(int scoreAdjustment) { this.scoreAdjustment = scoreAdjustment; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getAbTestGroup() { return abTestGroup; }
    public void setAbTestGroup(String abTestGroup) { this.abTestGroup = abTestGroup; }

    public BigDecimal getAbTestTraffic() { return abTestTraffic; }
    public void setAbTestTraffic(BigDecimal abTestTraffic) { this.abTestTraffic = abTestTraffic; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
