package io.nexuspay.payment.domain.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Records a completed currency conversion for a payment.
 * Immutable after creation.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public record CurrencyConversion(
        UUID id,
        String tenantId,
        String paymentId,
        String presentmentCurrency,
        long presentmentAmountMinorUnits,
        String settlementCurrency,
        long settlementAmountMinorUnits,
        BigDecimal appliedRate,
        int markupBps,
        String rateProvider,
        UUID rateLockId,
        Instant convertedAt
) {

    /**
     * Creates a conversion from a rate lock.
     */
    public static CurrencyConversion fromLock(
            String tenantId, String paymentId,
            long presentmentAmount, FxRateLock lock, int markupBps) {

        long settlementAmount = lock.convert(presentmentAmount);
        return new CurrencyConversion(
                UUID.randomUUID(),
                tenantId,
                paymentId,
                lock.getFromCurrency(),
                presentmentAmount,
                lock.getToCurrency(),
                settlementAmount,
                lock.getRate(),
                markupBps,
                lock.getRateProvider(),
                lock.getId(),
                Instant.now()
        );
    }

    /**
     * Calculates the FX gain/loss in settlement currency minor units.
     * Positive = gain, negative = loss.
     */
    public long fxGainLoss(BigDecimal currentRate) {
        long currentSettlement = BigDecimal.valueOf(presentmentAmountMinorUnits)
                .multiply(currentRate)
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .longValue();
        return settlementAmountMinorUnits - currentSettlement;
    }
}
