package io.nexuspay.ledger.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nexuspay.ledger.adapter.out.persistence.JpaLedgerAccountRepository;
import io.nexuspay.ledger.adapter.out.persistence.LedgerAccountEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the integrity/audit reconciliation job. Pins the join of
 * computeBalancesFromPostings() rows against accounts, the getOrDefault(0L)
 * edge for accounts with no postings, and the row parsing (id String,
 * amount via Number.longValue()). Drift is the entire purpose of the class,
 * so we assert on the WARN/ERROR drift log it emits.
 */
class BalanceReconciliationJobTest {

    private JpaLedgerAccountRepository accountRepository;
    private BalanceReconciliationJob job;
    private Logger jobLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        accountRepository = mock(JpaLedgerAccountRepository.class);
        job = new BalanceReconciliationJob(accountRepository);

        jobLogger = (Logger) LoggerFactory.getLogger(BalanceReconciliationJob.class);
        appender = new ListAppender<>();
        appender.start();
        jobLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        jobLogger.detachAppender(appender);
    }

    private LedgerAccountEntity entity(String id, long postedBalance) {
        return new LedgerAccountEntity(id, "name", "ASSET", "USD",
                postedBalance, 0L, "default", Instant.now(), Instant.now());
    }

    private boolean hasDriftWarn() {
        return appender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("BALANCE DRIFT"));
    }

    private boolean hasDriftErrorSummary() {
        return appender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("drift"));
    }

    @Test
    void noDrift_doesNotFlagWhenEveryComputedBalanceMatches() {
        when(accountRepository.computeBalancesFromPostings()).thenReturn(List.of(
                new Object[]{"la_a", 5000L},
                new Object[]{"la_b", -2000L}
        ));
        when(accountRepository.findAll()).thenReturn(List.of(
                entity("la_a", 5000L),
                entity("la_b", -2000L)
        ));

        job.reconcileBalances();

        assertThat(hasDriftWarn()).isFalse();
        assertThat(hasDriftErrorSummary()).isFalse();
        verify(accountRepository, times(1)).computeBalancesFromPostings();
        verify(accountRepository, times(1)).findAll();
    }

    @Test
    void drift_flaggedWhenPostedBalanceDiffersFromComputed() {
        when(accountRepository.computeBalancesFromPostings()).thenReturn(List.of(
                new Object[]{"la_a", 5000L}
        ));
        when(accountRepository.findAll()).thenReturn(List.of(
                entity("la_a", 4999L) // off by one minor unit
        ));

        job.reconcileBalances();

        assertThat(hasDriftWarn()).isTrue();
        assertThat(hasDriftErrorSummary()).isTrue();
    }

    @Test
    void multipleDriftingAccounts_allCounted() {
        when(accountRepository.computeBalancesFromPostings()).thenReturn(List.of(
                new Object[]{"la_a", 5000L},
                new Object[]{"la_b", 1000L},
                new Object[]{"la_c", 7000L}
        ));
        when(accountRepository.findAll()).thenReturn(List.of(
                entity("la_a", 5001L), // drift
                entity("la_b", 1000L), // ok
                entity("la_c", 6000L)  // drift
        ));

        job.reconcileBalances();

        long driftWarnings = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("BALANCE DRIFT"))
                .count();
        assertThat(driftWarnings).isEqualTo(2);
        // Error summary reports the drift count (2).
        String errorSummary = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .findFirst()
                .orElseThrow();
        assertThat(errorSummary).contains("2");
    }

    @Test
    void accountWithNoPostingsRow_defaultsToZero_driftOnlyIfBalanceNonZero() {
        // No computed row for la_orphan -> getOrDefault(0L). It carries a non-zero
        // posted balance, so it MUST be flagged as drift (the key edge).
        when(accountRepository.computeBalancesFromPostings()).thenReturn(List.of());
        when(accountRepository.findAll()).thenReturn(List.of(
                entity("la_orphan", 500L)
        ));

        job.reconcileBalances();

        assertThat(hasDriftWarn()).isTrue();
    }

    @Test
    void accountWithNoPostingsRowAndZeroBalance_isNotFlagged() {
        // getOrDefault(0L) == postedBalance(0) -> no drift.
        when(accountRepository.computeBalancesFromPostings()).thenReturn(List.of());
        when(accountRepository.findAll()).thenReturn(List.of(
                entity("la_fresh", 0L)
        ));

        job.reconcileBalances();

        assertThat(hasDriftWarn()).isFalse();
        assertThat(hasDriftErrorSummary()).isFalse();
    }

    @Test
    void rowParsing_handlesBigIntegerAndBigDecimalAmountsViaNumberLongValue() {
        // The DB driver may hand back BigInteger/BigDecimal for SUM(); row[1] is cast
        // via Number.longValue(). These match the posted balances exactly -> no drift.
        when(accountRepository.computeBalancesFromPostings()).thenReturn(List.of(
                new Object[]{"la_bi", BigInteger.valueOf(5000L)},
                new Object[]{"la_bd", new BigDecimal("-2000")}
        ));
        when(accountRepository.findAll()).thenReturn(List.of(
                entity("la_bi", 5000L),
                entity("la_bd", -2000L)
        ));

        job.reconcileBalances();

        assertThat(hasDriftWarn()).isFalse();
    }

    @Test
    void reconcilesNegativeBalancesExactly_noDrift() {
        when(accountRepository.computeBalancesFromPostings()).thenReturn(List.of(
                new Object[]{"la_liab", -99999L}
        ));
        when(accountRepository.findAll()).thenReturn(List.of(
                entity("la_liab", -99999L)
        ));

        job.reconcileBalances();

        assertThat(hasDriftWarn()).isFalse();
    }
}
