package io.nexuspay.marketplace.domain;

import io.nexuspay.common.domain.MoneyRounding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain model representing a platform fee charged on a split payment.
 * Records the fee amount deducted from the payment total before distribution.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public class PlatformFee {

    private String id;
    private String splitPaymentId;
    private String tenantId;
    private long feeAmount;
    private String currency;
    private BigDecimal feePercent;
    private long feeFixed;
    private String description;
    private Instant createdAt;

    public static PlatformFee create(String splitPaymentId, String tenantId, long feeAmount,
                                      String currency, BigDecimal feePercent, long feeFixed) {
        PlatformFee fee = new PlatformFee();
        fee.id = "pf_" + UUID.randomUUID().toString().replace("-", "");
        fee.splitPaymentId = splitPaymentId;
        fee.tenantId = tenantId;
        fee.feeAmount = feeAmount;
        fee.currency = currency;
        fee.feePercent = feePercent;
        fee.feeFixed = feeFixed;
        fee.createdAt = Instant.now();
        return fee;
    }

    /**
     * Calculates the total platform fee for a given payment amount.
     *
     * <p>SEC-19: the percentage component is computed in exact {@link BigDecimal} arithmetic via
     * {@link MoneyRounding#percentageOfMinorUnits} with {@link RoundingMode#HALF_EVEN} (banker's
     * rounding), replacing the previous {@code (long)(amount * percent.doubleValue() / 100.0)} float
     * math which lost fractional minor units and biased fees upward. {@code paymentAmount} is in minor
     * units, so rounding to scale 0 is correct for every currency (see {@code MoneyRounding}).</p>
     */
    public static long calculateFee(long paymentAmount, BigDecimal feePercent, long feeFixed) {
        long percentFee = MoneyRounding.percentageOfMinorUnits(paymentAmount, feePercent, RoundingMode.HALF_EVEN);
        return percentFee + feeFixed;
    }

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

    public long getFeeFixed() { return feeFixed; }
    public void setFeeFixed(long feeFixed) { this.feeFixed = feeFixed; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
