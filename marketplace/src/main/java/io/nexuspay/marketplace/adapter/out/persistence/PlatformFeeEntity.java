package io.nexuspay.marketplace.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity for the {@code platform_fees} table.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@Entity
@Table(name = "platform_fees")
public class PlatformFeeEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "split_payment_id", nullable = false, length = 64)
    private String splitPaymentId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "fee_amount", nullable = false)
    private long feeAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "fee_percent", precision = 5, scale = 2)
    private BigDecimal feePercent;

    @Column(name = "fee_fixed")
    private Long feeFixed;

    @Column(name = "description", length = 256)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSplitPaymentId() { return splitPaymentId; }
    public void setSplitPaymentId(String splitPaymentId) { this.splitPaymentId = splitPaymentId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public long getFeeAmount() { return feeAmount; }
    public void setFeeAmount(long feeAmount) { this.feeAmount = feeAmount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getFeePercent() { return feePercent; }
    public void setFeePercent(BigDecimal feePercent) { this.feePercent = feePercent; }

    public Long getFeeFixed() { return feeFixed; }
    public void setFeeFixed(Long feeFixed) { this.feeFixed = feeFixed; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
