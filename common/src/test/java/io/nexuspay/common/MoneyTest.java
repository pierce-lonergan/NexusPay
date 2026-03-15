package io.nexuspay.common;

import io.nexuspay.common.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    @Test
    void minorUnitsRoundTrip() {
        Money m = Money.ofMinorUnits(1050, "USD");
        assertEquals(1050, m.toMinorUnits());
        assertEquals(new BigDecimal("10.50"), m.toMajorUnits());
        assertEquals("USD", m.getCurrencyCode());
    }

    @Test
    void majorUnitsCreation() {
        Money m = Money.of(new BigDecimal("25.99"), "USD");
        assertEquals(2599, m.toMinorUnits());
    }

    @Test
    void zeroDecimalCurrency() {
        // JPY has no decimal places
        Money m = Money.ofMinorUnits(1000, "JPY");
        assertEquals(1000, m.toMinorUnits());
        assertEquals(new BigDecimal("1000"), m.toMajorUnits());
    }

    @Test
    void addSameCurrency() {
        Money a = Money.ofMinorUnits(500, "USD");
        Money b = Money.ofMinorUnits(300, "USD");
        Money result = a.add(b);
        assertEquals(800, result.toMinorUnits());
    }

    @Test
    void subtractSameCurrency() {
        Money a = Money.ofMinorUnits(500, "USD");
        Money b = Money.ofMinorUnits(300, "USD");
        Money result = a.subtract(b);
        assertEquals(200, result.toMinorUnits());
    }

    @Test
    void differentCurrencyThrows() {
        Money usd = Money.ofMinorUnits(100, "USD");
        Money eur = Money.ofMinorUnits(100, "EUR");
        assertThrows(IllegalArgumentException.class, () -> usd.add(eur));
    }

    @Test
    void negateProducesOpposite() {
        Money m = Money.ofMinorUnits(100, "USD");
        Money negated = m.negate();
        assertEquals(-100, negated.toMinorUnits());
        assertTrue(negated.isNegative());
    }

    @Test
    void equality() {
        Money a = Money.ofMinorUnits(100, "USD");
        Money b = Money.ofMinorUnits(100, "USD");
        assertEquals(a, b);
    }
}
