package io.nexuspay.payment.domain.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a locked FX rate for the duration of a payment lifecycle.
 * Once locked, the rate is guaranteed for the payment until expiry.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public class FxRateLock {

    private final UUID id;
    private final String tenantId;
    private String paymentId;
    private final String fromCurrency;
    private final String toCurrency;
    private final BigDecimal rate;
    private final BigDecimal inverseRate;
    private final String rateProvider;
    private final Instant lockedAt;
    private final Instant expiresAt;
    private boolean consumed;
    private Instant consumedAt;

    public FxRateLock(UUID id, String tenantId, String paymentId,
                      String fromCurrency, String toCurrency,
                      BigDecimal rate, BigDecimal inverseRate,
                      String rateProvider, Instant lockedAt, Instant expiresAt,
                      boolean consumed, Instant consumedAt) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.paymentId = paymentId;
        this.fromCurrency = Objects.requireNonNull(fromCurrency);
        this.toCurrency = Objects.requireNonNull(toCurrency);
        this.rate = Objects.requireNonNull(rate);
        this.inverseRate = Objects.requireNonNull(inverseRate);
        this.rateProvider = Objects.requireNonNull(rateProvider);
        this.lockedAt = Objects.requireNonNull(lockedAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.consumed = consumed;
        this.consumedAt = consumedAt;
    }

    /**
     * Creates a new rate lock from an FxRate with the specified duration.
     */
    public static FxRateLock create(String tenantId, FxRate fxRate, java.time.Duration lockDuration) {
        Instant now = Instant.now();
        return new FxRateLock(
                UUID.randomUUID(), tenantId, null,
                fxRate.pair().baseCurrency(), fxRate.pair().quoteCurrency(),
                fxRate.rate(), fxRate.inverseRate(),
                fxRate.provider(), now, now.plus(lockDuration),
                false, null
        );
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !consumed && !isExpired();
    }

    public void consume(String paymentId) {
        if (!isValid()) {
            throw new IllegalStateException("Cannot consume an expired or already-consumed rate lock");
        }
        this.paymentId = paymentId;
        this.consumed = true;
        this.consumedAt = Instant.now();
    }

    public void assignPayment(String paymentId) {
        this.paymentId = paymentId;
    }

    /**
     * Converts an amount using the locked rate, honoring each currency's ISO 4217
     * exponent (fromCurrency → toCurrency); see {@link CurrencyMath}.
     */
    public long convert(long amountMinorUnits) {
        return CurrencyMath.convert(amountMinorUnits, fromCurrency, toCurrency, rate);
    }

    // Getters
    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getPaymentId() { return paymentId; }
    public String getFromCurrency() { return fromCurrency; }
    public String getToCurrency() { return toCurrency; }
    public BigDecimal getRate() { return rate; }
    public BigDecimal getInverseRate() { return inverseRate; }
    public String getRateProvider() { return rateProvider; }
    public Instant getLockedAt() { return lockedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isConsumed() { return consumed; }
    public Instant getConsumedAt() { return consumedAt; }
}
