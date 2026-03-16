package io.nexuspay.payment.domain.routing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Represents a PSP's fee model for cost-based routing.
 * Supports per-transaction, percentage, blended, and interchange++ fee types.
 * <p>
 * Card-brand-specific pricing (GAP-049): Fee models can optionally be scoped
 * to a specific card brand (Visa, Mastercard), card type (credit, debit),
 * and domestic/international status. When multiple fee models match, the most
 * specific one is used (brand+type+domestic > brand+type > brand > default).
 *
 * @since 0.3.0 (Sprint 3.3)
 * @since 0.3.1 (GAP-049 — card-brand-specific pricing)
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
        LocalDate effectiveTo,
        String cardBrand,
        String cardType,
        Boolean isDomestic
) {

    /**
     * Backwards-compatible constructor without card-brand fields.
     */
    public PspFeeModel(
            UUID id, String tenantId, String pspConnector, FeeType feeType,
            BigDecimal perTxFee, BigDecimal percentageFee,
            int interchangeMarkupBps, int schemeFeeBps,
            String currency, LocalDate effectiveFrom, LocalDate effectiveTo) {
        this(id, tenantId, pspConnector, feeType, perTxFee, percentageFee,
                interchangeMarkupBps, schemeFeeBps, currency, effectiveFrom, effectiveTo,
                null, null, null);
    }

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

    /**
     * Calculates the specificity score for matching. Higher = more specific.
     * Used to select the most specific fee model when multiple match.
     * <pre>
     *   null brand + null type + null domestic = 0 (generic)
     *   brand only = 1
     *   brand + type = 2
     *   brand + type + domestic = 3
     * </pre>
     */
    public int specificity() {
        int score = 0;
        if (cardBrand != null) score++;
        if (cardType != null) score++;
        if (isDomestic != null) score++;
        return score;
    }

    /**
     * Whether this fee model matches the given card attributes.
     * A null field in the model means "any" (matches all values).
     */
    public boolean matchesCard(String brand, String type, Boolean domestic) {
        if (cardBrand != null && !cardBrand.equalsIgnoreCase(brand)) return false;
        if (cardType != null && !cardType.equalsIgnoreCase(type)) return false;
        if (isDomestic != null && domestic != null && !isDomestic.equals(domestic)) return false;
        return true;
    }
}
