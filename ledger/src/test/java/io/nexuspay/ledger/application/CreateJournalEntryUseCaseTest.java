package io.nexuspay.ledger.application;

import io.nexuspay.common.exception.LedgerException;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand.PostingLine;
import io.nexuspay.ledger.application.port.JournalEntryRepository;
import io.nexuspay.ledger.application.port.LedgerAccountRepository;
import io.nexuspay.ledger.domain.AccountType;
import io.nexuspay.ledger.domain.JournalEntry;
import io.nexuspay.ledger.domain.LedgerAccount;
import io.nexuspay.ledger.domain.Posting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the core money-math write path. Pins the per-posting balance
 * update, the account-not-found failure, the optimistic-concurrency retry loop
 * (MAX_OPTIMISTIC_RETRIES = 3) that protects balance integrity, and the
 * zero-sum invariant inherited from the JournalEntry constructor.
 */
class CreateJournalEntryUseCaseTest {

    private JournalEntryRepository journalEntryRepository;
    private LedgerAccountRepository ledgerAccountRepository;
    private CreateJournalEntryUseCase useCase;

    @BeforeEach
    void setUp() {
        journalEntryRepository = mock(JournalEntryRepository.class);
        ledgerAccountRepository = mock(LedgerAccountRepository.class);
        useCase = new CreateJournalEntryUseCase(journalEntryRepository, ledgerAccountRepository);
    }

    private LedgerAccount account(String id, long postedBalance, long version) {
        return new LedgerAccount(id, id, AccountType.ASSET, "USD",
                postedBalance, version, "default", Instant.now(), Instant.now());
    }

    private CreateJournalEntryCommand balancedCommand() {
        return new CreateJournalEntryCommand(
                "pi_test", "Payment captured", "default", Map.of(),
                List.of(
                        new PostingLine("la_merchant_recv_usd", 10000, "USD"),
                        new PostingLine("la_customer_liab_usd", -10000, "USD")
                ));
    }

    @Test
    void happyPath_updatesBalancePerLegWithExactComputedBalanceAndReturnsSavedEntry() {
        // merchant_recv starts at 5000 (version 7); customer_liab starts at -2000 (version 4)
        when(ledgerAccountRepository.findById("la_merchant_recv_usd"))
                .thenReturn(Optional.of(account("la_merchant_recv_usd", 5000, 7)));
        when(ledgerAccountRepository.findById("la_customer_liab_usd"))
                .thenReturn(Optional.of(account("la_customer_liab_usd", -2000, 4)));
        when(ledgerAccountRepository.updateBalanceWithVersion(anyString(), anyLong(), anyLong()))
                .thenReturn(true);
        // SEC-10: the write path now flushes synchronously (saveAndFlush) so a unique-violation race
        // surfaces inside the dup-key no-op rather than being deferred to commit.
        when(journalEntryRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(JournalEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        JournalEntry result = useCase.execute(balancedCommand());

        // merchant_recv: 5000 + 10000 = 15000 at version 7
        verify(ledgerAccountRepository).updateBalanceWithVersion("la_merchant_recv_usd", 15000L, 7L);
        // customer_liab: -2000 + (-10000) = -12000 at version 4
        verify(ledgerAccountRepository).updateBalanceWithVersion("la_customer_liab_usd", -12000L, 4L);

        // The saved entry is returned, with generated posting ids that preserve account/amount/currency.
        assertThat(result.getPaymentReference()).isEqualTo("pi_test");
        assertThat(result.getPostings()).hasSize(2);
        Posting merchantLeg = result.getPostings().stream()
                .filter(p -> p.ledgerAccountId().equals("la_merchant_recv_usd")).findFirst().orElseThrow();
        assertThat(merchantLeg.id()).startsWith("post_");
        assertThat(merchantLeg.amount()).isEqualTo(10000L);
        assertThat(merchantLeg.currency()).isEqualTo("USD");
        verify(journalEntryRepository).saveAndFlush(org.mockito.ArgumentMatchers.any(JournalEntry.class));
    }

    @Test
    void accountNotFound_throwsLedgerExceptionWithAccountNotFoundCode() {
        when(ledgerAccountRepository.findById("la_merchant_recv_usd")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(balancedCommand()))
                .isInstanceOf(LedgerException.class)
                .satisfies(ex -> assertThat(((LedgerException) ex).getErrorCode()).isEqualTo("account_not_found"));

        // Nothing was persisted because the first balance update failed.
        verify(journalEntryRepository, org.mockito.Mockito.never())
                .saveAndFlush(org.mockito.ArgumentMatchers.any(JournalEntry.class));
    }

    @Test
    void optimisticLock_retriesAndSucceedsAfterOneConflict_reReadsVersion() {
        when(ledgerAccountRepository.findById("la_merchant_recv_usd"))
                .thenReturn(Optional.of(account("la_merchant_recv_usd", 5000, 7)));
        when(ledgerAccountRepository.findById("la_customer_liab_usd"))
                .thenReturn(Optional.of(account("la_customer_liab_usd", -2000, 4)));
        // First account: conflict once (false) then succeed (true).
        when(ledgerAccountRepository.updateBalanceWithVersion(eq("la_merchant_recv_usd"), anyLong(), anyLong()))
                .thenReturn(false, true);
        when(ledgerAccountRepository.updateBalanceWithVersion(eq("la_customer_liab_usd"), anyLong(), anyLong()))
                .thenReturn(true);
        when(journalEntryRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(JournalEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        JournalEntry result = useCase.execute(balancedCommand());

        assertThat(result).isNotNull();
        // The conflicting account was re-read (findById) twice: once per attempt.
        verify(ledgerAccountRepository, times(2)).findById("la_merchant_recv_usd");
        verify(ledgerAccountRepository, times(1)).findById("la_customer_liab_usd");
        // Two update attempts on the conflicting account.
        verify(ledgerAccountRepository, times(2))
                .updateBalanceWithVersion(eq("la_merchant_recv_usd"), anyLong(), anyLong());
    }

    @Test
    void retryExhaustion_throwsConcurrencyConflictAfterExactlyThreeAttempts() {
        when(ledgerAccountRepository.findById("la_merchant_recv_usd"))
                .thenReturn(Optional.of(account("la_merchant_recv_usd", 5000, 7)));
        when(ledgerAccountRepository.findById("la_customer_liab_usd"))
                .thenReturn(Optional.of(account("la_customer_liab_usd", -2000, 4)));
        // Always conflict on the first account.
        when(ledgerAccountRepository.updateBalanceWithVersion(eq("la_merchant_recv_usd"), anyLong(), anyLong()))
                .thenReturn(false);

        assertThatThrownBy(() -> useCase.execute(balancedCommand()))
                .isInstanceOf(LedgerException.class)
                .satisfies(ex -> assertThat(((LedgerException) ex).getErrorCode()).isEqualTo("concurrency_conflict"));

        // findById re-invoked once per attempt — exactly MAX_OPTIMISTIC_RETRIES (3).
        verify(ledgerAccountRepository, times(3)).findById("la_merchant_recv_usd");
        verify(ledgerAccountRepository, times(3))
                .updateBalanceWithVersion(eq("la_merchant_recv_usd"), anyLong(), anyLong());
        verify(journalEntryRepository, org.mockito.Mockito.never())
                .saveAndFlush(org.mockito.ArgumentMatchers.any(JournalEntry.class));
    }

    @Test
    void unbalancedCommand_throwsBeforeAnySaveOrBalanceUpdate() {
        var unbalanced = new CreateJournalEntryCommand(
                "pi_bad", "Bad", "default", Map.of(),
                List.of(
                        new PostingLine("la_merchant_recv_usd", 10000, "USD"),
                        new PostingLine("la_customer_liab_usd", -5000, "USD")
                ));

        assertThatThrownBy(() -> useCase.execute(unbalanced))
                .isInstanceOf(LedgerException.class)
                .satisfies(ex -> assertThat(((LedgerException) ex).getErrorCode()).isEqualTo("unbalanced_entry"));

        // The JournalEntry constructor validates zero-sum, so this throws before any balance/IO touch.
        verify(ledgerAccountRepository, org.mockito.Mockito.never())
                .updateBalanceWithVersion(anyString(), anyLong(), anyLong());
        verify(journalEntryRepository, org.mockito.Mockito.never())
                .saveAndFlush(org.mockito.ArgumentMatchers.any(JournalEntry.class));
    }

    @Test
    void crossCurrencyNettingToZeroByRawSum_isRejectedPerCurrency() {
        // +100 USD / -100 JPY nets to 0 as a raw long sum but is unbalanced per currency.
        var crossCurrency = new CreateJournalEntryCommand(
                "pi_xccy", "Cross-currency", "default", Map.of(),
                List.of(
                        new PostingLine("la_merchant_recv_usd", 100, "USD"),
                        new PostingLine("la_customer_liab_jpy", -100, "JPY")
                ));

        assertThatThrownBy(() -> useCase.execute(crossCurrency))
                .isInstanceOf(LedgerException.class)
                .satisfies(ex -> assertThat(((LedgerException) ex).getErrorCode()).isEqualTo("unbalanced_entry"));

        verify(ledgerAccountRepository, org.mockito.Mockito.never())
                .updateBalanceWithVersion(anyString(), anyLong(), anyLong());
        verify(journalEntryRepository, org.mockito.Mockito.never())
                .saveAndFlush(org.mockito.ArgumentMatchers.any(JournalEntry.class));
    }
}
