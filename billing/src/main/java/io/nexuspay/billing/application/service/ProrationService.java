package io.nexuspay.billing.application.service;

import io.nexuspay.billing.domain.Price;
import io.nexuspay.billing.domain.Subscription;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Calculates prorated amounts for mid-cycle plan changes.
 *
 * <p>When a customer upgrades or downgrades mid-billing-cycle, this service
 * computes the credit for unused time on the old plan and the charge for
 * remaining time on the new plan.</p>
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
@Service
public class ProrationService {

    /**
     * Calculates proration for a plan change.
     *
     * @param subscription  current subscription
     * @param oldPrice      current price
     * @param newPrice      target price
     * @param changeDate    when the change takes effect
     * @return proration breakdown
     */
    public ProrationResult calculate(Subscription subscription, Price oldPrice,
                                      Price newPrice, Instant changeDate) {

        long daysInPeriod = Duration.between(
                subscription.getCurrentPeriodStart(),
                subscription.getCurrentPeriodEnd()
        ).toDays();

        if (daysInPeriod <= 0) daysInPeriod = 1; // safety

        long daysRemaining = Duration.between(changeDate, subscription.getCurrentPeriodEnd()).toDays();
        if (daysRemaining < 0) daysRemaining = 0;

        long oldAmount = oldPrice.calculateAmount(subscription.getQuantity());
        long newAmount = newPrice.calculateAmount(subscription.getQuantity());

        // Credit for unused time on old plan
        long unusedCredit = (oldAmount * daysRemaining) / daysInPeriod;

        // Charge for remaining time on new plan
        long newCharge = (newAmount * daysRemaining) / daysInPeriod;

        long netAmount = newCharge - unusedCredit;

        return new ProrationResult(unusedCredit, newCharge, netAmount);
    }

    /**
     * Proration breakdown.
     *
     * @param unusedCredit  credit for remaining time on old plan (positive)
     * @param newCharge     charge for remaining time on new plan (positive)
     * @param netAmount     net amount to charge (positive = charge, negative = credit)
     */
    public record ProrationResult(long unusedCredit, long newCharge, long netAmount) {
        public boolean isUpgrade() {
            return netAmount > 0;
        }

        public boolean isDowngrade() {
            return netAmount < 0;
        }
    }
}
