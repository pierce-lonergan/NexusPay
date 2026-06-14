package io.nexuspay.ledger.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the BigDecimal FX P&L accumulator.
 * The key invariant under test: recordRealized ACCUMULATES (add) while
 * updateUnrealized REPLACES — and netPosition = realized + unrealized.
 */
class FxGainLossAccountTest {

    @Test
    void create_initializesZeroBalancesNonNullIdAndTimestamp() {
        FxGainLossAccount acct = FxGainLossAccount.create("tenant-1", "USD/EUR", "la_fx_gain_loss_usd_eur");

        assertThat(acct.getId()).isNotNull();
        assertThat(acct.getTenantId()).isEqualTo("tenant-1");
        assertThat(acct.getCurrencyPair()).isEqualTo("USD/EUR");
        assertThat(acct.getAccountId()).isEqualTo("la_fx_gain_loss_usd_eur");
        assertThat(acct.getRealizedGainLoss()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(acct.getUnrealizedGainLoss()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(acct.getLastCalculatedAt()).isNotNull();
    }

    @Test
    void recordRealized_accumulatesAcrossCalls() {
        FxGainLossAccount acct = FxGainLossAccount.create("t", "USD/EUR", "la_x");

        acct.recordRealized(new BigDecimal("100.50"));
        acct.recordRealized(new BigDecimal("-30.25"));

        // 100.50 + (-30.25) = 70.25 — compareTo avoids scale equality pitfalls.
        assertThat(acct.getRealizedGainLoss()).isEqualByComparingTo(new BigDecimal("70.25"));
    }

    @Test
    void recordRealized_advancesLastCalculatedAt() throws InterruptedException {
        FxGainLossAccount acct = FxGainLossAccount.create("t", "USD/EUR", "la_x");
        Instant before = acct.getLastCalculatedAt();

        Thread.sleep(2);
        acct.recordRealized(new BigDecimal("5.00"));

        assertThat(acct.getLastCalculatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void recordRealized_negativeAmountDecreasesBalance() {
        FxGainLossAccount acct = FxGainLossAccount.create("t", "USD/EUR", "la_x");

        acct.recordRealized(new BigDecimal("200.00"));
        acct.recordRealized(new BigDecimal("-250.00"));

        // A loss larger than the prior gain takes the balance negative.
        assertThat(acct.getRealizedGainLoss()).isEqualByComparingTo(new BigDecimal("-50.00"));
        assertThat(acct.getRealizedGainLoss().signum()).isNegative();
    }

    @Test
    void updateUnrealized_replacesRatherThanAdds() {
        FxGainLossAccount acct = FxGainLossAccount.create("t", "USD/EUR", "la_x");

        acct.updateUnrealized(new BigDecimal("5"));
        acct.updateUnrealized(new BigDecimal("9"));

        // REPLACE semantics: second call wins; it is NOT 5 + 9 = 14.
        assertThat(acct.getUnrealizedGainLoss()).isEqualByComparingTo(new BigDecimal("9"));
    }

    @Test
    void netPosition_isRealizedPlusUnrealizedAfterMixOfCalls() {
        FxGainLossAccount acct = FxGainLossAccount.create("t", "USD/EUR", "la_x");

        acct.recordRealized(new BigDecimal("100.00"));
        acct.recordRealized(new BigDecimal("25.50"));   // realized = 125.50
        acct.updateUnrealized(new BigDecimal("10.00")); // replace
        acct.updateUnrealized(new BigDecimal("-40.00")); // replace -> unrealized = -40.00

        // net = 125.50 + (-40.00) = 85.50
        assertThat(acct.netPosition()).isEqualByComparingTo(new BigDecimal("85.50"));
    }

    @Test
    void scaleIsPreservedOnExactCentValues() {
        FxGainLossAccount acct = FxGainLossAccount.create("t", "USD/EUR", "la_x");

        acct.recordRealized(new BigDecimal("100.50"));

        // Value compares equal to 100.50 and retains 2-decimal scale.
        assertThat(acct.getRealizedGainLoss()).isEqualByComparingTo(new BigDecimal("100.50"));
        assertThat(acct.getRealizedGainLoss().scale()).isEqualTo(2);
    }

    @Test
    void recordRealizedZero_leavesBalanceUnchanged() {
        FxGainLossAccount acct = FxGainLossAccount.create("t", "USD/EUR", "la_x");

        acct.recordRealized(new BigDecimal("42.00"));
        acct.recordRealized(BigDecimal.ZERO);

        assertThat(acct.getRealizedGainLoss()).isEqualByComparingTo(new BigDecimal("42.00"));
    }
}
