package io.nexuspay.payment.domain.fx;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-014: FX conversion must honor each currency's ISO 4217 exponent. The prior
 * {@code amount * rate} on raw minor units was 100× wrong for USD→JPY and 10×
 * for 3-decimal currencies. These tests pin the corrected behavior and the
 * delegating FxRate/FxRateLock convert() methods.
 */
class CurrencyMathTest {

    @Test
    void fractionDigitsPerIso4217() {
        assertThat(CurrencyMath.fractionDigits("USD")).isEqualTo(2);
        assertThat(CurrencyMath.fractionDigits("JPY")).isEqualTo(0);
        assertThat(CurrencyMath.fractionDigits("BHD")).isEqualTo(3);
        assertThat(CurrencyMath.fractionDigits("zzz")).as("unknown → assume 2").isEqualTo(2);
    }

    @Test
    void usdToJpy_isNot100xOff() {
        // $10.00 = 1000 minor; 1 USD = 150 JPY  ->  ¥1500 = 1500 minor (NOT 150000).
        assertThat(CurrencyMath.convert(1000, "USD", "JPY", new BigDecimal("150")))
                .isEqualTo(1500);
    }

    @Test
    void sameExponentPair() {
        // $100.00 = 10000 minor; 1 USD = 0.9 EUR  ->  €90.00 = 9000 minor.
        assertThat(CurrencyMath.convert(10000, "USD", "EUR", new BigDecimal("0.9")))
                .isEqualTo(9000);
    }

    @Test
    void threeDecimalQuoteCurrency() {
        // $100.00 = 10000 minor; 1 USD = 0.376 BHD  ->  BHD 37.600 = 37600 minor.
        assertThat(CurrencyMath.convert(10000, "USD", "BHD", new BigDecimal("0.376")))
                .isEqualTo(37600);
    }

    @Test
    void zeroDecimalBaseCurrency() {
        // ¥1500 = 1500 minor; 1 JPY = 0.0066667 USD  ->  $10.00 = 1000 minor.
        assertThat(CurrencyMath.convert(1500, "JPY", "USD", new BigDecimal("0.0066667")))
                .isEqualTo(1000);
    }

    @Test
    void fxRateConvertDelegatesAndIsExponentCorrect() {
        FxRate r = FxRate.of("USD", "JPY", new BigDecimal("150"), "test");
        assertThat(r.convert(1000)).isEqualTo(1500);
    }

    @Test
    void fxRateLockConvertDelegatesAndIsExponentCorrect() {
        FxRateLock lock = new FxRateLock(UUID.randomUUID(), "t1", null,
                "USD", "JPY", new BigDecimal("150"), new BigDecimal("0.0066667"),
                "test", Instant.now(), Instant.now().plusSeconds(300), false, null);
        assertThat(lock.convert(1000)).isEqualTo(1500);
    }

    @Test
    void fxGainLossIsInSettlementMinorUnits() {
        // Locked $10.00 @150 -> ¥1500. Current rate 151 -> ¥1510. Loss = -10 (yen),
        // NOT the old raw ~ -150000.
        FxRateLock lock = new FxRateLock(UUID.randomUUID(), "t1", null,
                "USD", "JPY", new BigDecimal("150"), new BigDecimal("0.0066667"),
                "test", Instant.now(), Instant.now().plusSeconds(300), false, null);
        CurrencyConversion conv = CurrencyConversion.fromLock("t1", "pay_1", 1000, lock, 0);
        assertThat(conv.settlementAmountMinorUnits()).isEqualTo(1500);
        assertThat(conv.fxGainLoss(new BigDecimal("151"))).isEqualTo(-10);
    }
}
