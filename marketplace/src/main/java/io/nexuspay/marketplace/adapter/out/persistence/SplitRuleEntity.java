package io.nexuspay.marketplace.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity for the {@code split_rules} table.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@Entity
@Table(name = "split_rules")
public class SplitRuleEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "split_payment_id", nullable = false, length = 64)
    private String splitPaymentId;

    @Column(name = "connected_account_id", nullable = false, length = 64)
    private String connectedAccountId;

    @Column(name = "split_type", nullable = false, length = 16)
    private String splitType;

    @Column(name = "amount")
    private Long amount;

    @Column(name = "percentage", precision = 5, scale = 2)
    private BigDecimal percentage;

    @Column(name = "calculated_amount")
    private Long calculatedAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSplitPaymentId() { return splitPaymentId; }
    public void setSplitPaymentId(String splitPaymentId) { this.splitPaymentId = splitPaymentId; }

    public String getConnectedAccountId() { return connectedAccountId; }
    public void setConnectedAccountId(String connectedAccountId) { this.connectedAccountId = connectedAccountId; }

    public String getSplitType() { return splitType; }
    public void setSplitType(String splitType) { this.splitType = splitType; }

    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }

    public BigDecimal getPercentage() { return percentage; }
    public void setPercentage(BigDecimal percentage) { this.percentage = percentage; }

    public Long getCalculatedAmount() { return calculatedAmount; }
    public void setCalculatedAmount(Long calculatedAmount) { this.calculatedAmount = calculatedAmount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
