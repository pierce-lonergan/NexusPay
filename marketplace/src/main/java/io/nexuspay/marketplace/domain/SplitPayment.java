package io.nexuspay.marketplace.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Domain model representing a payment split across multiple connected accounts.
 * Holds the parent payment reference and the set of split rules that define
 * how proceeds are distributed.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public class SplitPayment {

    private String id;
    private String paymentId;
    private String tenantId;
    private SplitPaymentStatus status;
    private long totalAmount;
    private String currency;
    private List<SplitRule> rules;
    private Instant createdAt;

    public static SplitPayment create(String paymentId, String tenantId, long totalAmount,
                                       String currency) {
        SplitPayment sp = new SplitPayment();
        sp.id = "sp_" + UUID.randomUUID().toString().replace("-", "");
        sp.paymentId = paymentId;
        sp.tenantId = tenantId;
        sp.status = SplitPaymentStatus.PENDING;
        sp.totalAmount = totalAmount;
        sp.currency = currency;
        sp.rules = new ArrayList<>();
        sp.createdAt = Instant.now();
        return sp;
    }

    public void addRule(SplitRule rule) {
        this.rules.add(rule);
    }

    public void markProcessing() {
        this.status = SplitPaymentStatus.PROCESSING;
    }

    public void markCompleted() {
        this.status = SplitPaymentStatus.COMPLETED;
    }

    public void markFailed() {
        this.status = SplitPaymentStatus.FAILED;
    }

    /**
     * Resolves calculated amounts for all rules based on totalAmount.
     * PERCENTAGE rules get their share, FIXED rules keep their amount,
     * REMAINDER receives whatever is left.
     */
    public void resolveAmounts() {
        long allocated = 0;
        SplitRule remainderRule = null;

        for (SplitRule rule : rules) {
            switch (rule.getSplitType()) {
                case PERCENTAGE -> {
                    long amount = (long) (totalAmount * rule.getPercentage().doubleValue() / 100.0);
                    rule.setCalculatedAmount(amount);
                    allocated += amount;
                }
                case FIXED -> {
                    rule.setCalculatedAmount(rule.getAmount());
                    allocated += rule.getAmount();
                }
                case REMAINDER -> remainderRule = rule;
            }
        }

        if (remainderRule != null) {
            remainderRule.setCalculatedAmount(totalAmount - allocated);
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public SplitPaymentStatus getStatus() { return status; }
    public void setStatus(SplitPaymentStatus status) { this.status = status; }

    public long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(long totalAmount) { this.totalAmount = totalAmount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public List<SplitRule> getRules() { return rules; }
    public void setRules(List<SplitRule> rules) { this.rules = rules; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
