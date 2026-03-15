package io.nexuspay.common.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Type-safe money value object.
 * Stores amounts in minor currency units (cents for USD, yen for JPY).
 * Uses java.util.Currency for ISO 4217 compliance.
 */
public final class Money {

    private final long amountInMinorUnits;
    private final Currency currency;

    private Money(long amountInMinorUnits, Currency currency) {
        this.amountInMinorUnits = amountInMinorUnits;
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
    }

    /**
     * Creates Money from minor units (e.g., 1000 cents = $10.00 USD).
     */
    public static Money ofMinorUnits(long amount, String currencyCode) {
        return new Money(amount, Currency.getInstance(currencyCode.toUpperCase()));
    }

    /**
     * Creates Money from a major unit decimal (e.g., 10.00 USD = 1000 cents).
     */
    public static Money of(BigDecimal amount, String currencyCode) {
        Currency cur = Currency.getInstance(currencyCode.toUpperCase());
        int fraction = cur.getDefaultFractionDigits();
        long minorUnits = amount.movePointRight(Math.max(fraction, 0)).longValueExact();
        return new Money(minorUnits, cur);
    }

    public long toMinorUnits() {
        return amountInMinorUnits;
    }

    public BigDecimal toMajorUnits() {
        int fraction = currency.getDefaultFractionDigits();
        return BigDecimal.valueOf(amountInMinorUnits, Math.max(fraction, 0));
    }

    public String getCurrencyCode() {
        return currency.getCurrencyCode();
    }

    public Currency getCurrency() {
        return currency;
    }

    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(this.amountInMinorUnits + other.amountInMinorUnits, this.currency);
    }

    public Money subtract(Money other) {
        assertSameCurrency(other);
        return new Money(this.amountInMinorUnits - other.amountInMinorUnits, this.currency);
    }

    public Money negate() {
        return new Money(-this.amountInMinorUnits, this.currency);
    }

    public boolean isPositive() {
        return amountInMinorUnits > 0;
    }

    public boolean isNegative() {
        return amountInMinorUnits < 0;
    }

    public boolean isZero() {
        return amountInMinorUnits == 0;
    }

    private void assertSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot operate on different currencies: " + this.currency + " vs " + other.currency);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return amountInMinorUnits == money.amountInMinorUnits && currency.equals(money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amountInMinorUnits, currency);
    }

    @Override
    public String toString() {
        return toMajorUnits().toPlainString() + " " + currency.getCurrencyCode();
    }
}
