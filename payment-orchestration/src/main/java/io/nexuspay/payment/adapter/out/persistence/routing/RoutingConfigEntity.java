package io.nexuspay.payment.adapter.out.persistence.routing;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for tenant routing configurations.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Entity
@Table(name = "routing_configs")
public class RoutingConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "config_name", nullable = false)
    private String configName;

    @Column(nullable = false)
    private String strategy;

    @Column(name = "psp_list", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private String pspList;

    @Column(name = "cascade_enabled", nullable = false)
    private boolean cascadeEnabled = true;

    @Column(name = "max_cascade_depth", nullable = false)
    private int maxCascadeDepth = 3;

    @Column(name = "filters", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private String filters;

    @Column(name = "ab_test_id")
    private UUID abTestId;

    @Column(name = "ab_test_traffic")
    private Double abTestTraffic;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getConfigName() { return configName; }
    public void setConfigName(String configName) { this.configName = configName; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public String getPspList() { return pspList; }
    public void setPspList(String pspList) { this.pspList = pspList; }
    public boolean isCascadeEnabled() { return cascadeEnabled; }
    public void setCascadeEnabled(boolean cascadeEnabled) { this.cascadeEnabled = cascadeEnabled; }
    public int getMaxCascadeDepth() { return maxCascadeDepth; }
    public void setMaxCascadeDepth(int maxCascadeDepth) { this.maxCascadeDepth = maxCascadeDepth; }
    public String getFilters() { return filters; }
    public void setFilters(String filters) { this.filters = filters; }
    public UUID getAbTestId() { return abTestId; }
    public void setAbTestId(UUID abTestId) { this.abTestId = abTestId; }
    public Double getAbTestTraffic() { return abTestTraffic; }
    public void setAbTestTraffic(Double abTestTraffic) { this.abTestTraffic = abTestTraffic; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
