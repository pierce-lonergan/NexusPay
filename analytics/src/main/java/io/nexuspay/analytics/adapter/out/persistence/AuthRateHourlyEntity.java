package io.nexuspay.analytics.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code analytics.auth_rate_hourly} table.
 * <p>Stores hourly authorisation-rate aggregates broken down by PSP connector
 * and optional card/payment dimensions.</p>
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Entity
@Table(name = "auth_rate_hourly", schema = "analytics")
public class AuthRateHourlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "bucket_hour", nullable = false)
    private Instant bucketHour;

    @Column(name = "psp_connector", nullable = false)
    private String pspConnector;

    @Column(name = "card_brand")
    private String cardBrand;

    @Column(name = "card_type")
    private String cardType;

    @Column(name = "issuing_region")
    private String issuingRegion;

    @Column(name = "currency")
    private String currency;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "total_attempts", nullable = false)
    private int totalAttempts;

    @Column(name = "total_approved", nullable = false)
    private int totalApproved;

    @Column(name = "total_declined", nullable = false)
    private int totalDeclined;

    @Column(name = "total_errors", nullable = false)
    private int totalErrors;

    @Column(name = "auth_rate", nullable = false)
    private BigDecimal authRate;

    @Column(name = "avg_latency_ms")
    private Integer avgLatencyMs;

    @Column(name = "p50_latency_ms")
    private Integer p50LatencyMs;

    @Column(name = "p95_latency_ms")
    private Integer p95LatencyMs;

    @Column(name = "p99_latency_ms")
    private Integer p99LatencyMs;

    protected AuthRateHourlyEntity() {}

    public AuthRateHourlyEntity(UUID id, String tenantId, Instant bucketHour,
                                String pspConnector, String cardBrand, String cardType,
                                String issuingRegion, String currency, String paymentMethod,
                                int totalAttempts, int totalApproved, int totalDeclined,
                                int totalErrors, BigDecimal authRate, Integer avgLatencyMs,
                                Integer p50LatencyMs, Integer p95LatencyMs, Integer p99LatencyMs) {
        this.id = id;
        this.tenantId = tenantId;
        this.bucketHour = bucketHour;
        this.pspConnector = pspConnector;
        this.cardBrand = cardBrand;
        this.cardType = cardType;
        this.issuingRegion = issuingRegion;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.totalAttempts = totalAttempts;
        this.totalApproved = totalApproved;
        this.totalDeclined = totalDeclined;
        this.totalErrors = totalErrors;
        this.authRate = authRate;
        this.avgLatencyMs = avgLatencyMs;
        this.p50LatencyMs = p50LatencyMs;
        this.p95LatencyMs = p95LatencyMs;
        this.p99LatencyMs = p99LatencyMs;
    }

    // --- Getters & Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Instant getBucketHour() { return bucketHour; }
    public void setBucketHour(Instant bucketHour) { this.bucketHour = bucketHour; }
    public String getPspConnector() { return pspConnector; }
    public void setPspConnector(String pspConnector) { this.pspConnector = pspConnector; }
    public String getCardBrand() { return cardBrand; }
    public void setCardBrand(String cardBrand) { this.cardBrand = cardBrand; }
    public String getCardType() { return cardType; }
    public void setCardType(String cardType) { this.cardType = cardType; }
    public String getIssuingRegion() { return issuingRegion; }
    public void setIssuingRegion(String issuingRegion) { this.issuingRegion = issuingRegion; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public int getTotalAttempts() { return totalAttempts; }
    public void setTotalAttempts(int totalAttempts) { this.totalAttempts = totalAttempts; }
    public int getTotalApproved() { return totalApproved; }
    public void setTotalApproved(int totalApproved) { this.totalApproved = totalApproved; }
    public int getTotalDeclined() { return totalDeclined; }
    public void setTotalDeclined(int totalDeclined) { this.totalDeclined = totalDeclined; }
    public int getTotalErrors() { return totalErrors; }
    public void setTotalErrors(int totalErrors) { this.totalErrors = totalErrors; }
    public BigDecimal getAuthRate() { return authRate; }
    public void setAuthRate(BigDecimal authRate) { this.authRate = authRate; }
    public Integer getAvgLatencyMs() { return avgLatencyMs; }
    public void setAvgLatencyMs(Integer avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }
    public Integer getP50LatencyMs() { return p50LatencyMs; }
    public void setP50LatencyMs(Integer p50LatencyMs) { this.p50LatencyMs = p50LatencyMs; }
    public Integer getP95LatencyMs() { return p95LatencyMs; }
    public void setP95LatencyMs(Integer p95LatencyMs) { this.p95LatencyMs = p95LatencyMs; }
    public Integer getP99LatencyMs() { return p99LatencyMs; }
    public void setP99LatencyMs(Integer p99LatencyMs) { this.p99LatencyMs = p99LatencyMs; }
}
