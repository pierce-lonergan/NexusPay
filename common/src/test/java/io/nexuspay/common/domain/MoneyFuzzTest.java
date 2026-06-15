package io.nexuspay.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adversarial-INPUT fuzz of the {@link Money} value object (in-gate, UNTAGGED).
 *
 * <p>This is part of the simulation / red-team environment (see
 * {@code docs/simulation/README.md}). It targets the ALREADY-CORRECT money
 * primitive, so it PASSES on current main and is therefore safe to run in the
 * default {@code ./gradlew test} gate (it is intentionally NOT
 * {@code @Tag("redteam")} / {@code @Tag("simulation")}). It is deterministic,
 * table-driven {@code @ParameterizedTest} (NOT a random property engine — jqwik
 * is not wired into the gate per {@code .perpetua/ratchets.json}
 * {@code tooling.property}), so it cannot flake.</p>
 *
 * <p><strong>Money-invariant claim:</strong> no operation silently drops or
 * fabricates value. Round-trips are exact (long minor units / BigDecimal major
 * units), and every arithmetic overflow THROWS {@link ArithmeticException}
 * (via {@code Math.*Exact} / {@code BigDecimal.longValueExact}) rather than
 * wrapping around to a wrong amount.</p>
 */
@DisplayName("Money — adversarial input fuzz (in-gate, no-money-drop)")
class MoneyFuzzTest {

    // ---- minor-unit round-trip is exact across the full long range + boundaries ----

    @ParameterizedTest(name = "minor-unit round-trip is exact: {0}")
    @ValueSource(longs = {
            0L, 1L, -1L, 99L, 100L, 12345L, -12345L,
            999_999_999L, -999_999_999L,
            Long.MAX_VALUE, Long.MIN_VALUE,
            Long.MAX_VALUE - 1, Long.MIN_VALUE + 1
    })
    void minorUnitRoundTrip_isExact(long minor) {
        Money m = Money.ofMinorUnits(minor, "USD");
        // Exact long comparison — no BigDecimal drift, no truncation.
        assertThat(m.toMinorUnits()).isEqualTo(minor);
    }

    @ParameterizedTest(name = "major<->minor round-trip is exact: {0} {1}")
    @MethodSource("majorMinorPairs")
    void majorToMinor_roundTrip_isExact(String major, String currency, long expectedMinor) {
        Money fromMajor = Money.of(new BigDecimal(major), currency);
        assertThat(fromMajor.toMinorUnits())
                .as("major->minor must not drop fractional cents")
                .isEqualTo(expectedMinor);
        // And back: minor->major must reproduce the canonical decimal.
        Money fromMinor = Money.ofMinorUnits(expectedMinor, currency);
        assertThat(fromMinor.toMajorUnits().compareTo(new BigDecimal(major)))
                .as("minor->major round-trip equals original major value")
                .isZero();
    }

    static Stream<Arguments> majorMinorPairs() {
        return Stream.of(
                Arguments.of("0.00", "USD", 0L),
                Arguments.of("0.01", "USD", 1L),
                Arguments.of("10.50", "USD", 1050L),
                Arguments.of("25.99", "USD", 2599L),
                Arguments.of("99999999.99", "USD", 9_999_999_999L),
                Arguments.of("-12.34", "USD", -1234L),
                // Zero-decimal currency: 1000 JPY == 1000 minor units (no x100).
                Arguments.of("1000", "JPY", 1000L),
                Arguments.of("0", "JPY", 0L),
                Arguments.of("-5", "JPY", -5L),
                // Three-decimal currency: 1.234 KWD == 1234 minor units.
                Arguments.of("1.234", "KWD", 1234L),
                Arguments.of("0.001", "KWD", 1L)
        );
    }

    @Test
    @DisplayName("JPY zero-decimal: minor units ARE major units (no implicit x100)")
    void jpyZeroDecimal_minorEqualsMajor() {
        Money jpy = Money.ofMinorUnits(123456, "JPY");
        assertThat(jpy.toMinorUnits()).isEqualTo(123456L);
        assertThat(jpy.toMajorUnits()).isEqualByComparingTo(new BigDecimal("123456"));
    }

    // ---- BigDecimal inputs that would lose money MUST throw, never truncate ----

    @ParameterizedTest(name = "sub-cent precision rejected (no silent truncation): {0}")
    @ValueSource(strings = {"10.001", "0.005", "1.999", "-0.001", "99.12345"})
    void of_subCentPrecision_throws_neverTruncates(String major) {
        // longValueExact() throws on a non-integral scaled value — money is never
        // rounded away silently (the classic "lost half-cent" bug).
        assertThatThrownBy(() -> Money.of(new BigDecimal(major), "USD"))
                .isInstanceOf(ArithmeticException.class);
    }

    @ParameterizedTest(name = "major value beyond long range rejected: {0}")
    @ValueSource(strings = {
            "92233720368547758.08",   // > Long.MAX_VALUE cents
            "-92233720368547758.09",  // < Long.MIN_VALUE cents
            "999999999999999999999"   // absurdly large integer
    })
    void of_beyondLongRange_throws_neverWrapsAround(String major) {
        assertThatThrownBy(() -> Money.of(new BigDecimal(major), "USD"))
                .isInstanceOf(ArithmeticException.class);
    }

    // ---- arithmetic overflow at the minor-unit boundary MUST throw ----

    @Test
    @DisplayName("add overflow throws ArithmeticException (no wrap to negative)")
    void add_overflow_throws() {
        Money max = Money.ofMinorUnits(Long.MAX_VALUE, "USD");
        Money one = Money.ofMinorUnits(1, "USD");
        assertThatThrownBy(() -> max.add(one)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("subtract underflow throws ArithmeticException (no wrap to positive)")
    void subtract_underflow_throws() {
        Money min = Money.ofMinorUnits(Long.MIN_VALUE, "USD");
        Money one = Money.ofMinorUnits(1, "USD");
        assertThatThrownBy(() -> min.subtract(one)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("negate of Long.MIN_VALUE throws (no asymmetric two's-complement wrap)")
    void negate_minValue_throws() {
        Money min = Money.ofMinorUnits(Long.MIN_VALUE, "USD");
        assertThatThrownBy(min::negate).isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("add at the boundary that does NOT overflow stays exact")
    void add_atBoundary_withoutOverflow_isExact() {
        Money a = Money.ofMinorUnits(Long.MAX_VALUE - 5, "USD");
        Money b = Money.ofMinorUnits(5, "USD");
        assertThatCode(() -> {
            Money sum = a.add(b);
            assertThat(sum.toMinorUnits()).isEqualTo(Long.MAX_VALUE);
        }).doesNotThrowAnyException();
    }

    // ---- mismatched currencies never silently combine (no implicit FX) ----

    @ParameterizedTest(name = "cross-currency {0}+{1} rejected (no implicit FX)")
    @MethodSource("crossCurrencyPairs")
    void crossCurrencyArithmetic_throws(String ccyA, String ccyB) {
        Money a = Money.ofMinorUnits(100, ccyA);
        Money b = Money.ofMinorUnits(100, ccyB);
        assertThatThrownBy(() -> a.add(b)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.subtract(b)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> crossCurrencyPairs() {
        return Stream.of(
                Arguments.of("USD", "EUR"),
                Arguments.of("USD", "JPY"),
                Arguments.of("GBP", "USD"),
                Arguments.of("EUR", "KWD")
        );
    }

    // ---- malformed currency codes are rejected, not coerced ----

    @ParameterizedTest(name = "invalid currency code rejected: \"{0}\"")
    @ValueSource(strings = {"US", "USDD", "123", "$$$", "zz", "  "})
    void ofMinorUnits_invalidCurrency_throws(String ccy) {
        assertThatThrownBy(() -> Money.ofMinorUnits(100, ccy))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("lowercase currency code is normalized, not rejected")
    void ofMinorUnits_lowercaseCurrency_isNormalized() {
        Money m = Money.ofMinorUnits(100, "usd");
        assertThat(m.getCurrencyCode()).isEqualTo("USD");
    }
}
