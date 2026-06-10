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
     *
     * <p>Guarantees the calculated amounts reconcile to {@code totalAmount}
     * exactly: rejects negative or over-allocating rules, and when there is no
     * REMAINDER rule, assigns the rounding leftover deterministically to the
     * largest leg. Without this, percentages summing to &lt;100% silently drop
     * funds and &gt;100% (or oversized FIXED amounts) produce a negative
     * payout leg.</p>
     */
    public void resolveAmounts() {
        long allocated = 0;
        SplitRule remainderRule = null;
        SplitRule largestRule = null;
        long largestAmount = -1;

        for (SplitRule rule : rules) {
            switch (rule.getSplitType()) {
                case PERCENTAGE -> {
                    if (rule.getPercentage() == null || rule.getPercentage().signum() < 0) {
                        throw new IllegalArgumentException("Split percentage must be non-negative");
                    }
                    long amount = (long) (totalAmount * rule.getPercentage().doubleValue() / 100.0);
                    rule.setCalculatedAmount(amount);
                    allocated += amount;
                    if (amount > largestAmount) { largestAmount = amount; largestRule = rule; }
                }
                case FIXED -> {
                    if (rule.getAmount() < 0) {
                        throw new IllegalArgumentException("Split fixed amount must be non-negative");
                    }
                    rule.setCalculatedAmount(rule.getAmount());
                    allocated += rule.getAmount();
                    if (rule.getAmount() > largestAmount) { largestAmount = rule.getAmount(); largestRule = rule; }
                }
                case REMAINDER -> remainderRule = rule;
            }
        }

        if (allocated > totalAmount) {
            throw new IllegalStateException(
                    "Split rules over-allocate: " + allocated + " > total " + totalAmount);
        }

        long unallocated = totalAmount - allocated;
        if (remainderRule != null) {
            remainderRule.setCalculatedAmount(unallocated);
        } else if (unallocated != 0) {
            if (largestRule == null) {
                throw new IllegalStateException(
                        "Split has no allocatable rule to absorb " + unallocated + " of " + totalAmount);
            }
            largestRule.setCalculatedAmount(largestRule.getCalculatedAmount() + unallocated);
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
