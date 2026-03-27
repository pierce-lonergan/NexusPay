package io.nexuspay.analytics.adapter.out.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code analytics.psp_health_snapshots} table.
 * <p>Point-in-time health scores for each PSP connector, combining auth rate,
 * latency, and error rate sub-scores with optional anomaly detection metadata.</p>
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Entity
@Table(name = "psp_health_snapshots", schema = "analytics")
public class PspHealthSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "psp_connector", nullable = false)
    private String pspConnector;

    @Column(name = "snapshot_time", nullable = false)
    private Instant snapshotTime;

    @Column(name = "health_score", nullable = false)
    private int healthScore;

    @Column(name = "auth_rate_score", nullable = false)
    private int authRateScore;

    @Column(name = "latency_score", nullable = false)
    private int latencyScore;

    @Column(name = "error_rate_score", nullable = false)
    private int errorRateScore;

    @Column(name = "auth_rate_7d")
    private BigDecimal authRate7d;

    @Column(name = "avg_latency_ms")
    private Integer avgLatencyMs;

    @Column(name = "p95_latency_ms")
    private Integer p95LatencyMs;

    @Column(name = "error_rate")
    private BigDecimal errorRate;

    @Column(name = "anomaly_detected", nullable = false)
    private boolean anomalyDetected;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "anomaly_details", columnDefinition = "jsonb")
    private String anomalyDetails;

    protected PspHealthSnapshotEntity() {}

    public PspHealthSnapshotEntity(UUID id, String tenantId, String pspConnector,
                                   Instant snapshotTime, int healthScore, int authRateScore,
                                   int latencyScore, int errorRateScore, BigDecimal authRate7d,
                                   Integer avgLatencyMs, Integer p95LatencyMs,
                                   BigDecimal errorRate, boolean anomalyDetected,
                                   String anomalyDetails) {
        this.id = id;
        this.tenantId = tenantId;
        this.pspConnector = pspConnector;
        this.snapshotTime = snapshotTime;
        this.healthScore = healthScore;
        this.authRateScore = authRateScore;
        this.latencyScore = latencyScore;
        this.errorRateScore = errorRateScore;
        this.authRate7d = authRate7d;
        this.avgLatencyMs = avgLatencyMs;
        this.p95LatencyMs = p95LatencyMs;
        this.errorRate = errorRate;
        this.anomalyDetected = anomalyDetected;
        this.anomalyDetails = anomalyDetails;
    }

    // --- Getters & Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPspConnector() { return pspConnector; }
    public void setPspConnector(String pspConnector) { this.pspConnector = pspConnector; }
    public Instant getSnapshotTime() { return snapshotTime; }
    public void setSnapshotTime(Instant snapshotTime) { this.snapshotTime = snapshotTime; }
    public int getHealthScore() { return healthScore; }
    public void setHealthScore(int healthScore) { this.healthScore = healthScore; }
    public int getAuthRateScore() { return authRateScore; }
    public void setAuthRateScore(int authRateScore) { this.authRateScore = authRateScore; }
    public int getLatencyScore() { return latencyScore; }
    public void setLatencyScore(int latencyScore) { this.latencyScore = latencyScore; }
    public int getErrorRateScore() { return errorRateScore; }
    public void setErrorRateScore(int errorRateScore) { this.errorRateScore = errorRateScore; }
    public BigDecimal getAuthRate7d() { return authRate7d; }
    public void setAuthRate7d(BigDecimal authRate7d) { this.authRate7d = authRate7d; }
    public Integer getAvgLatencyMs() { return avgLatencyMs; }
    public void setAvgLatencyMs(Integer avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }
    public Integer getP95LatencyMs() { return p95LatencyMs; }
    public void setP95LatencyMs(Integer p95LatencyMs) { this.p95LatencyMs = p95LatencyMs; }
    public BigDecimal getErrorRate() { return errorRate; }
    public void setErrorRate(BigDecimal errorRate) { this.errorRate = errorRate; }
    public boolean isAnomalyDetected() { return anomalyDetected; }
    public void setAnomalyDetected(boolean anomalyDetected) { this.anomalyDetected = anomalyDetected; }
    public String getAnomalyDetails() { return anomalyDetails; }
    public void setAnomalyDetails(String anomalyDetails) { this.anomalyDetails = anomalyDetails; }
}
