package io.nexuspay.payment.domain.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a Dynamic Currency Conversion (DCC) offer presented to a cardholder.
 * DCC allows customers to pay in their home currency at the point of sale,
 * with the conversion handled by the merchant's acquirer rather than the card network.
 * <p>
 * The DCC flow:
 * 1. Merchant creates payment in local (presentment) currency
 * 2. System detects cardholder's home currency differs from presentment currency
 * 3. DCC offer is generated with the converted amount and markup
 * 4. Cardholder chooses to accept DCC (pay in home currency) or decline (pay in presentment)
 * 5. If accepted, payment proceeds with the DCC rate; the PSP is notified of DCC election
 *
 * @since 0.3.1 (GAP-044)
 */
public record DccOffer(
        UUID id,
        String tenantId,
        String paymentId,
        String presentmentCurrency,
        long presentmentAmountMinorUnits,
        String cardholderCurrency,
        long cardholderAmountMinorUnits,
        BigDecimal offeredRate,
        int markupBps,
        BigDecimal marginAmount,
        String rateProvider,
        Instant offeredAt,
        Instant expiresAt,
        DccStatus status
) {

    public enum DccStatus {
        /** Offer presented to cardholder, awaiting decision. */
        OFFERED,
        /** Cardholder accepted DCC — payment proceeds in cardholder currency. */
        ACCEPTED,
        /** Cardholder declined DCC — payment proceeds in presentment currency. */
        DECLINED,
        /** Offer expired before cardholder responded. */
        EXPIRED
    }

    /**
     * Creates a new DCC offer from a rate with merchant markup applied.
     *
     * @param tenantId                   the merchant tenant
     * @param paymentId                  the payment being offered DCC
     * @param presentmentCurrency        the original payment currency
     * @param presentmentAmountMinorUnits the original amount in minor units
     * @param cardholderCurrency         the cardholder's home currency
     * @param rate                       the FX rate (already includes markup)
     * @param markupBps                  the DCC markup in basis points
     * @param offerValidityMinutes       how long the offer is valid
     */
    public static DccOffer create(
            String tenantId, String paymentId,
            String presentmentCurrency, long presentmentAmountMinorUnits,
            String cardholderCurrency, FxRate rate, int markupBps,
            int offerValidityMinutes) {

        FxRate markedUpRate = rate.withMarkup(markupBps);
        long cardholderAmount = markedUpRate.convert(presentmentAmountMinorUnits);
        long marginAmount = cardholderAmount - rate.convert(presentmentAmountMinorUnits);

        Instant now = Instant.now();
        return new DccOffer(
                UUID.randomUUID(),
                tenantId,
                paymentId,
                presentmentCurrency,
                presentmentAmountMinorUnits,
                cardholderCurrency,
                cardholderAmount,
                markedUpRate.rate(),
                markupBps,
                BigDecimal.valueOf(marginAmount),
                rate.provider(),
                now,
                now.plusSeconds((long) offerValidityMinutes * 60),
                DccStatus.OFFERED
        );
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isActionable() {
        return status == DccStatus.OFFERED && !isExpired();
    }

    public DccOffer accept() {
        if (!isActionable()) {
            throw new IllegalStateException("DCC offer is not actionable (status=" + status + ", expired=" + isExpired() + ")");
        }
        return new DccOffer(id, tenantId, paymentId, presentmentCurrency, presentmentAmountMinorUnits,
                cardholderCurrency, cardholderAmountMinorUnits, offeredRate, markupBps,
                marginAmount, rateProvider, offeredAt, expiresAt, DccStatus.ACCEPTED);
    }

    public DccOffer decline() {
        if (!isActionable()) {
            throw new IllegalStateException("DCC offer is not actionable (status=" + status + ", expired=" + isExpired() + ")");
        }
        return new DccOffer(id, tenantId, paymentId, presentmentCurrency, presentmentAmountMinorUnits,
                cardholderCurrency, cardholderAmountMinorUnits, offeredRate, markupBps,
                marginAmount, rateProvider, offeredAt, expiresAt, DccStatus.DECLINED);
    }
}
