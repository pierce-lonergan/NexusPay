package io.nexuspay.marketplace.domain;

import io.nexuspay.common.domain.MoneyRounding;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
     * REMAINDER rule, reconciles the rounding delta deterministically across the
     * percentage/fixed legs. Without this, percentages summing to &lt;100% silently
     * drop funds and &gt;100% (or oversized FIXED amounts) produce a negative
     * payout leg.</p>
     */
    public void resolveAmounts() {
        long allocated = 0;
        // SEC-BATCH-5c (money-math BLOCKER): the EXACT, un-rounded intended allocation in minor units.
        // SEC-19 switched each PERCENTAGE leg from a truncating float cast (always rounds DOWN) to
        // HALF_EVEN, which can round a leg UP. With 3+ legs whose percentages sum to exactly 100%, the
        // independently-rounded legs can sum to MORE than totalAmount by up to ~half a minor unit per
        // leg — a pure ROUNDING surplus on otherwise-valid input (e.g. 33.33/33.33/33.34 of 999999, or
        // 8x12.5% of 12). That must NOT throw. A GENUINE over-allocation (percentages summing to >100%,
        // or oversized FIXED amounts) is detected against this exact total, which rounding cannot mask.
        BigDecimal exactAllocated = BigDecimal.ZERO;
        SplitRule remainderRule = null;
        // Percentage/fixed legs in declaration order, used to redistribute the rounding delta
        // deterministically (largest-remainder method) without ever driving a leg negative.
        List<SplitRule> distributableRules = new ArrayList<>();

        for (SplitRule rule : rules) {
            switch (rule.getSplitType()) {
                case PERCENTAGE -> {
                    if (rule.getPercentage() == null || rule.getPercentage().signum() < 0) {
                        throw new IllegalArgumentException("Split percentage must be non-negative");
                    }
                    // SEC-19: exact BigDecimal percentage with banker's rounding, replacing the prior
                    // (long)(total * percent.doubleValue() / 100.0) float math. The reconciliation below
                    // keeps the legs summing EXACTLY to totalAmount — this only changes how each leg is
                    // rounded.
                    long amount = MoneyRounding.percentageOfMinorUnits(
                            totalAmount, rule.getPercentage(), RoundingMode.HALF_EVEN);
                    rule.setCalculatedAmount(amount);
                    allocated += amount;
                    // Exact (un-rounded) share: totalAmount * percent / 100. Dividing by 100 (= 2^2*5^2)
                    // always terminates, so no scale/rounding is needed and no ArithmeticException arises.
                    exactAllocated = exactAllocated.add(BigDecimal.valueOf(totalAmount)
                            .multiply(rule.getPercentage())
                            .divide(BigDecimal.valueOf(100)));
                    distributableRules.add(rule);
                }
                case FIXED -> {
                    if (rule.getAmount() < 0) {
                        throw new IllegalArgumentException("Split fixed amount must be non-negative");
                    }
                    rule.setCalculatedAmount(rule.getAmount());
                    allocated += rule.getAmount();
                    exactAllocated = exactAllocated.add(BigDecimal.valueOf(rule.getAmount()));
                    distributableRules.add(rule);
                }
                case REMAINDER -> remainderRule = rule;
            }
        }

        // GENUINE over-allocation: the exact intended allocation exceeds the total (percentages summing
        // to >100% or FIXED amounts too large). Rounding can never inflate the exact sum, so this guard
        // fires ONLY on truly-invalid input — never on a HALF_EVEN rounding surplus.
        if (exactAllocated.compareTo(BigDecimal.valueOf(totalAmount)) > 0) {
            throw new IllegalStateException(
                    "Split rules over-allocate: " + exactAllocated.stripTrailingZeros().toPlainString()
                            + " > total " + totalAmount);
        }

        long delta = totalAmount - allocated;
        if (remainderRule != null) {
            // With a REMAINDER rule, the rounded percentage/fixed legs cannot exceed totalAmount once the
            // genuine-over-allocation guard above has passed (the rounded sum stays within half a minor
            // unit per leg of the exact sum, which is <= total). The REMAINDER absorbs whatever is left.
            // Any residual rounding surplus (delta < 0, possible only with many legs) is reconciled
            // against the distributable legs the same way as the no-remainder case.
            if (delta >= 0) {
                remainderRule.setCalculatedAmount(delta);
            } else {
                remainderRule.setCalculatedAmount(0);
                redistributeDelta(distributableRules, delta);
            }
        } else if (delta != 0) {
            // No REMAINDER rule: reconcile the rounding delta across the distributable legs. delta > 0
            // (rounded legs fell short, e.g. percentages summing to <100%) ADDS units; delta < 0 (rounded
            // legs over-shot the exact total by a HALF_EVEN surplus — the SEC-BATCH-5c BLOCKER) SUBTRACTS
            // units. Spreading one unit per leg (largest-remainder style) keeps every leg non-negative
            // and the sum EXACTLY equal to totalAmount.
            if (distributableRules.isEmpty()) {
                throw new IllegalStateException(
                        "Split has no allocatable rule to absorb " + delta + " of " + totalAmount);
            }
            redistributeDelta(distributableRules, delta);
        }
    }

    /**
     * SEC-BATCH-5c: spreads a rounding {@code delta} (positive = under-allocated, negative =
     * over-allocated) across the distributable (PERCENTAGE/FIXED) legs one minor unit at a time so the
     * legs sum EXACTLY to {@code totalAmount}. Positive deltas are added to the largest legs first;
     * negative deltas are subtracted from the largest legs first, and a leg is skipped once it would go
     * negative so no payout leg is ever driven below zero. {@code |delta|} is bounded by the number of
     * legs (HALF_EVEN rounds each leg by < 1 minor unit), so a single pass over the legs ordered by
     * current amount suffices.
     */
    private void redistributeDelta(List<SplitRule> legs, long delta) {
        if (delta == 0 || legs.isEmpty()) {
            return;
        }
        // Largest current amount first: a stable copy keeps the assignment deterministic and leaves the
        // declaration order of the underlying rules untouched.
        List<SplitRule> ordered = new ArrayList<>(legs);
        ordered.sort((a, b) -> Long.compare(b.getCalculatedAmount(), a.getCalculatedAmount()));

        long remaining = delta;
        // Loop until the whole delta is placed. With |delta| <= legs.size() the first pass settles a
        // positive delta; a negative delta may need extra passes if some legs are skipped at zero, but
        // total positive capacity (sum of leg amounts) always covers a rounding-scale deficit.
        while (remaining != 0) {
            boolean progressed = false;
            for (SplitRule leg : ordered) {
                if (remaining == 0) {
                    break;
                }
                if (remaining > 0) {
                    leg.setCalculatedAmount(leg.getCalculatedAmount() + 1);
                    remaining--;
                    progressed = true;
                } else if (leg.getCalculatedAmount() > 0) {
                    leg.setCalculatedAmount(leg.getCalculatedAmount() - 1);
                    remaining++;
                    progressed = true;
                }
            }
            if (!progressed) {
                // Cannot place the remaining delta without driving a leg negative — only reachable if the
                // total distributable amount is smaller than the deficit, which the over-allocation guard
                // already excludes for rounding-scale deltas.
                throw new IllegalStateException(
                        "Split cannot reconcile rounding delta " + remaining + " of " + totalAmount);
            }
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
