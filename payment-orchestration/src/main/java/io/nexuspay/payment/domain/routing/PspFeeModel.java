package io.nexuspay.payment.domain.routing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Represents a PSP's fee model for cost-based routing.
 * Supports per-transaction, percentage, blended, and interchange++ fee types.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
public record PspFeeModel(
        UUID id,
        String tenantId,
        String pspConnector,
        FeeType feeType,
        BigDecimal perTxFee,
        BigDecimal percentageFee,
        int interchangeMarkupBps,
        int schemeFeeBps,
        String currency,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {

    public enum FeeType {
        PER_TX,
        PERCENTAGE,
        BLENDED,
        INTERCHANGE_PLUS_PLUS
    }

    /**
     * Calculates the effective fee for a given transaction amount.
     */
    public BigDecimal calculateFee(BigDecimal amount) {
        return switch (feeType) {
            case PER_TX -> perTxFee != null ? perTxFee : BigDecimal.ZERO;
            case PERCENTAGE -> percentageFee != null
                    ? amount.multiply(percentageFee).setScale(4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            case BLENDED -> {
                BigDecimal pctFee = percentageFee != null
                        ? amount.multiply(percentageFee).setScale(4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                BigDecimal txFee = perTxFee != null ? perTxFee : BigDecimal.ZERO;
                yield pctFee.add(txFee);
            }
            case INTERCHANGE_PLUS_PLUS -> {
                BigDecimal totalBps = BigDecimal.valueOf(interchangeMarkupBps + schemeFeeBps);
                yield amount.multiply(totalBps)
                        .divide(BigDecimal.valueOf(10000), 4, RoundingMode.HALF_UP);
            }
        };
    }

    /**
     * Checks if this fee model is effective on the given date.
     */
    public boolean isEffective(LocalDate date) {
        if (date.isBefore(effectiveFrom)) return false;
        return effectiveTo == null || !date.isAfter(effectiveTo);
    }
}
