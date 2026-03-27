package io.nexuspay.marketplace.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain model representing a single rule within a split payment.
 * Defines how much a connected account receives: by percentage, fixed amount,
 * or the remainder after other rules are applied.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public class SplitRule {

    private String id;
    private String splitPaymentId;
    private String connectedAccountId;
    private SplitType splitType;
    private long amount;
    private BigDecimal percentage;
    private long calculatedAmount;
    private String currency;
    private Instant createdAt;

    public static SplitRule create(String splitPaymentId, String connectedAccountId,
                                    SplitType splitType, long amount, BigDecimal percentage,
                                    String currency) {
        SplitRule rule = new SplitRule();
        rule.id = "sr_" + UUID.randomUUID().toString().replace("-", "");
        rule.splitPaymentId = splitPaymentId;
        rule.connectedAccountId = connectedAccountId;
        rule.splitType = splitType;
        rule.amount = amount;
        rule.percentage = percentage;
        rule.calculatedAmount = 0;
        rule.currency = currency;
        rule.createdAt = Instant.now();
        return rule;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSplitPaymentId() { return splitPaymentId; }
    public void setSplitPaymentId(String splitPaymentId) { this.splitPaymentId = splitPaymentId; }

    public String getConnectedAccountId() { return connectedAccountId; }
    public void setConnectedAccountId(String connectedAccountId) { this.connectedAccountId = connectedAccountId; }

    public SplitType getSplitType() { return splitType; }
    public void setSplitType(SplitType splitType) { this.splitType = splitType; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public BigDecimal getPercentage() { return percentage; }
    public void setPercentage(BigDecimal percentage) { this.percentage = percentage; }

    public long getCalculatedAmount() { return calculatedAmount; }
    public void setCalculatedAmount(long calculatedAmount) { this.calculatedAmount = calculatedAmount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
