package io.nexuspay.analytics.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code analytics.revenue_hourly} table.
 * <p>Hourly revenue aggregates including volume, fees, net revenue,
 * refunds, and chargebacks.</p>
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Entity
@Table(name = "revenue_hourly", schema = "analytics")
public class RevenueHourlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "bucket_hour", nullable = false)
    private Instant bucketHour;

    @Column(name = "psp_connector")
    private String pspConnector;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "total_volume", nullable = false)
    private BigDecimal totalVolume;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "total_fees", nullable = false)
    private BigDecimal totalFees;

    @Column(name = "net_revenue", nullable = false)
    private BigDecimal netRevenue;

    @Column(name = "refund_volume", nullable = false)
    private BigDecimal refundVolume;

    @Column(name = "refund_count", nullable = false)
    private int refundCount;

    @Column(name = "chargeback_volume", nullable = false)
    private BigDecimal chargebackVolume;

    @Column(name = "chargeback_count", nullable = false)
    private int chargebackCount;

    protected RevenueHourlyEntity() {}

    public RevenueHourlyEntity(UUID id, String tenantId, Instant bucketHour,
                               String pspConnector, String currency, String paymentMethod,
                               BigDecimal totalVolume, int totalCount, BigDecimal totalFees,
                               BigDecimal netRevenue, BigDecimal refundVolume, int refundCount,
                               BigDecimal chargebackVolume, int chargebackCount) {
        this.id = id;
        this.tenantId = tenantId;
        this.bucketHour = bucketHour;
        this.pspConnector = pspConnector;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.totalVolume = totalVolume;
        this.totalCount = totalCount;
        this.totalFees = totalFees;
        this.netRevenue = netRevenue;
        this.refundVolume = refundVolume;
        this.refundCount = refundCount;
        this.chargebackVolume = chargebackVolume;
        this.chargebackCount = chargebackCount;
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
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public BigDecimal getTotalVolume() { return totalVolume; }
    public void setTotalVolume(BigDecimal totalVolume) { this.totalVolume = totalVolume; }
    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
    public BigDecimal getTotalFees() { return totalFees; }
    public void setTotalFees(BigDecimal totalFees) { this.totalFees = totalFees; }
    public BigDecimal getNetRevenue() { return netRevenue; }
    public void setNetRevenue(BigDecimal netRevenue) { this.netRevenue = netRevenue; }
    public BigDecimal getRefundVolume() { return refundVolume; }
    public void setRefundVolume(BigDecimal refundVolume) { this.refundVolume = refundVolume; }
    public int getRefundCount() { return refundCount; }
    public void setRefundCount(int refundCount) { this.refundCount = refundCount; }
    public BigDecimal getChargebackVolume() { return chargebackVolume; }
    public void setChargebackVolume(BigDecimal chargebackVolume) { this.chargebackVolume = chargebackVolume; }
    public int getChargebackCount() { return chargebackCount; }
    public void setChargebackCount(int chargebackCount) { this.chargebackCount = chargebackCount; }
}
