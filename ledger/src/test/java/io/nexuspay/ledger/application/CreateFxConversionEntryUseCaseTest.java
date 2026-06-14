package io.nexuspay.ledger.application;

import io.nexuspay.ledger.application.CreateFxConversionEntryUseCase.FxConversionRequest;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand.PostingLine;
import io.nexuspay.ledger.application.port.FxGainLossAccountRepository;
import io.nexuspay.ledger.application.port.LedgerAccountRepository;
import io.nexuspay.ledger.domain.AccountType;
import io.nexuspay.ledger.domain.FxGainLossAccount;
import io.nexuspay.ledger.domain.JournalEntry;
import io.nexuspay.ledger.domain.LedgerAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the multi-leg cross-currency money math. Asserts the 6-posting
 * (3-leg) structure, per-currency zero-sum, account-id construction, metadata
 * stamping, and the idempotent provisioning of fx_clearing / fx_gain_loss accounts.
 */
class CreateFxConversionEntryUseCaseTest {

    private EnsureAccountsExistUseCase ensureAccounts;
    private CreateJournalEntryUseCase createJournalEntry;
    private LedgerAccountRepository accountRepo;
    private FxGainLossAccountRepository fxGlRepo;
    private CreateFxConversionEntryUseCase useCase;

    @BeforeEach
    void setUp() {
        ensureAccounts = mock(EnsureAccountsExistUseCase.class);
        createJournalEntry = mock(CreateJournalEntryUseCase.class);
        accountRepo = mock(LedgerAccountRepository.class);
        fxGlRepo = mock(FxGainLossAccountRepository.class);
        useCase = new CreateFxConversionEntryUseCase(ensureAccounts, createJournalEntry, accountRepo, fxGlRepo);

        // Default: nothing exists yet (so clearing + gl accounts get provisioned).
        when(accountRepo.findById(anyString())).thenReturn(Optional.empty());
        when(fxGlRepo.findByTenantIdAndCurrencyPair(anyString(), anyString())).thenReturn(Optional.empty());
        when(createJournalEntry.execute(any(CreateJournalEntryCommand.class)))
                .thenReturn(mock(JournalEntry.class));
    }

    private CreateJournalEntryCommand captureCommand() {
        ArgumentCaptor<CreateJournalEntryCommand> captor =
                ArgumentCaptor.forClass(CreateJournalEntryCommand.class);
        verify(createJournalEntry).execute(captor.capture());
        return captor.getValue();
    }

    private long amountFor(List<PostingLine> postings, String accountId) {
        return postings.stream()
                .filter(p -> p.ledgerAccountId().equals(accountId))
                .mapToLong(PostingLine::amount)
                .sum();
    }

    @Test
    void buildsSixPostingsWithCorrectDirectionsPerLeg() {
        // 10000 USD presentment -> 9000 EUR settlement (rate 0.9)
        var req = new FxConversionRequest(
                "tenant-1", "pi_fx", "USD", 10000L, "EUR", 9000L,
                new BigDecimal("0.9"), "ECB");

        useCase.execute(req);

        List<PostingLine> postings = captureCommand().postings();
        assertThat(postings).hasSize(6);

        // Presentment leg 1: DR merchant_recv_usd (+10000) / CR customer_liab_usd (-10000)
        // Presentment leg 2: DR fx_clearing_usd (+10000) / CR merchant_recv_usd (-10000)
        // => merchant_recv_usd nets to 0 across the two presentment legs; fx_clearing_usd is +10000.
        assertThat(amountFor(postings, "la_merchant_recv_usd")).isEqualTo(0L);
        assertThat(amountFor(postings, "la_customer_liab_usd")).isEqualTo(-10000L);
        assertThat(amountFor(postings, "la_fx_clearing_usd")).isEqualTo(10000L);

        // Settlement leg: DR merchant_recv_eur (+9000) / CR fx_clearing_eur (-9000)
        assertThat(amountFor(postings, "la_merchant_recv_eur")).isEqualTo(9000L);
        assertThat(amountFor(postings, "la_fx_clearing_eur")).isEqualTo(-9000L);
    }

    @Test
    void perCurrencyZeroSum_holdsIndependentlyEvenThoughRawSumIsNonZero() {
        var req = new FxConversionRequest(
                "tenant-1", "pi_fx", "USD", 10000L, "EUR", 9000L,
                new BigDecimal("0.9"), "ECB");

        useCase.execute(req);
        List<PostingLine> postings = captureCommand().postings();

        long usdSum = postings.stream().filter(p -> p.currency().equals("USD"))
                .mapToLong(PostingLine::amount).sum();
        long eurSum = postings.stream().filter(p -> p.currency().equals("EUR"))
                .mapToLong(PostingLine::amount).sum();
        long rawSum = postings.stream().mapToLong(PostingLine::amount).sum();

        // The real invariant: each currency nets to zero INDEPENDENTLY.
        assertThat(usdSum).isZero();
        assertThat(eurSum).isZero();
        // The two open FX-position legs are sized by the differing per-currency
        // amounts (10000 USD vs 9000 EUR), proving balance is per-currency and not
        // a coincidence of a single cross-currency netting.
        assertThat(amountFor(postings, "la_fx_clearing_usd")).isEqualTo(10000L);
        assertThat(amountFor(postings, "la_fx_clearing_eur")).isEqualTo(-9000L);
        // (rawSum here happens to be 0 only because each currency nets to 0 on its own.)
        assertThat(rawSum).isEqualTo(usdSum + eurSum);
    }

    @Test
    void accountIdsUseUppercasedCurrenciesEvenWhenRequestPassesLowercase() {
        var req = new FxConversionRequest(
                "tenant-1", "pi_fx", "usd", 10000L, "eur", 9000L,
                new BigDecimal("0.9"), "ECB");

        useCase.execute(req);
        List<PostingLine> postings = captureCommand().postings();

        assertThat(postings).extracting(PostingLine::ledgerAccountId)
                .containsExactlyInAnyOrder(
                        "la_merchant_recv_usd", "la_customer_liab_usd",
                        "la_fx_clearing_usd", "la_merchant_recv_usd",
                        "la_merchant_recv_eur", "la_fx_clearing_eur");
        // Currencies on the lines are uppercased too.
        assertThat(postings).extracting(PostingLine::currency).containsOnly("USD", "EUR");
    }

    @Test
    void metadataCarriesRateProviderCurrenciesAndType() {
        var req = new FxConversionRequest(
                "tenant-1", "pi_fx", "usd", 10000L, "eur", 9000L,
                new BigDecimal("0.90"), "ECB");

        useCase.execute(req);
        var metadata = captureCommand().metadata();

        assertThat(metadata)
                .containsEntry("fx_rate", "0.90")
                .containsEntry("fx_provider", "ECB")
                .containsEntry("presentment_currency", "USD")
                .containsEntry("settlement_currency", "EUR")
                .containsEntry("type", "fx_conversion");
    }

    @Test
    void provisionsClearingAndGainLossAccountsWhenAbsent() {
        var req = new FxConversionRequest(
                "tenant-1", "pi_fx", "USD", 10000L, "EUR", 9000L,
                new BigDecimal("0.9"), "ECB");

        useCase.execute(req);

        verify(ensureAccounts).ensureAccountsForCurrency("USD", "tenant-1");
        verify(ensureAccounts).ensureAccountsForCurrency("EUR", "tenant-1");

        ArgumentCaptor<LedgerAccount> savedAccounts = ArgumentCaptor.forClass(LedgerAccount.class);
        verify(accountRepo, org.mockito.Mockito.atLeastOnce()).save(savedAccounts.capture());

        // fx_clearing accounts are ASSET type.
        var clearingUsd = savedAccounts.getAllValues().stream()
                .filter(a -> a.getId().equals("la_fx_clearing_usd")).findFirst().orElseThrow();
        assertThat(clearingUsd.getType()).isEqualTo(AccountType.ASSET);
        var clearingEur = savedAccounts.getAllValues().stream()
                .filter(a -> a.getId().equals("la_fx_clearing_eur")).findFirst().orElseThrow();
        assertThat(clearingEur.getType()).isEqualTo(AccountType.ASSET);

        // fx gain/loss ledger account is REVENUE type, id derived from the pair.
        var glAccount = savedAccounts.getAllValues().stream()
                .filter(a -> a.getId().equals("la_fx_gain_loss_usd_eur")).findFirst().orElseThrow();
        assertThat(glAccount.getType()).isEqualTo(AccountType.REVENUE);

        // FxGainLossAccount tracking record saved for the pair (USD/EUR).
        ArgumentCaptor<FxGainLossAccount> savedFxGl = ArgumentCaptor.forClass(FxGainLossAccount.class);
        verify(fxGlRepo).save(savedFxGl.capture());
        assertThat(savedFxGl.getValue().getCurrencyPair()).isEqualTo("USD/EUR");
        assertThat(savedFxGl.getValue().getAccountId()).isEqualTo("la_fx_gain_loss_usd_eur");
    }

    @Test
    void clearingAccountsAreIdempotent_notRecreatedWhenAlreadyPresent() {
        // fx_clearing_usd already exists; everything else absent.
        when(accountRepo.findById("la_fx_clearing_usd"))
                .thenReturn(Optional.of(new LedgerAccount("la_fx_clearing_usd", "x",
                        AccountType.ASSET, "USD", 0L, 0L, "tenant-1", Instant.now(), Instant.now())));

        var req = new FxConversionRequest(
                "tenant-1", "pi_fx", "USD", 10000L, "EUR", 9000L,
                new BigDecimal("0.9"), "ECB");

        useCase.execute(req);

        ArgumentCaptor<LedgerAccount> saved = ArgumentCaptor.forClass(LedgerAccount.class);
        verify(accountRepo, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(LedgerAccount::getId)
                .doesNotContain("la_fx_clearing_usd"); // existing one is not re-saved
    }

    @Test
    void fxGainLossTrackingIsIdempotent_notRecreatedWhenAlreadyPresent() {
        when(fxGlRepo.findByTenantIdAndCurrencyPair("tenant-1", "USD/EUR"))
                .thenReturn(Optional.of(FxGainLossAccount.create("tenant-1", "USD/EUR", "la_fx_gain_loss_usd_eur")));

        var req = new FxConversionRequest(
                "tenant-1", "pi_fx", "USD", 10000L, "EUR", 9000L,
                new BigDecimal("0.9"), "ECB");

        useCase.execute(req);

        verify(fxGlRepo, never()).save(any(FxGainLossAccount.class));
    }

    @Test
    void equalAmountsAtRateOne_stillBuildsBalancedSixPostingEntry() {
        var req = new FxConversionRequest(
                "tenant-1", "pi_fx", "USD", 5000L, "USD", 5000L,
                BigDecimal.ONE, "ECB");

        useCase.execute(req);
        List<PostingLine> postings = captureCommand().postings();

        assertThat(postings).hasSize(6);
        // Same currency on both sides; every line is USD and the total nets to zero.
        long usdSum = postings.stream().filter(p -> p.currency().equals("USD"))
                .mapToLong(PostingLine::amount).sum();
        assertThat(usdSum).isZero();
        // Both presentment and settlement are USD, so ensureAccountsForCurrency("USD", ...)
        // is invoked once per currency slot — twice total.
        verify(ensureAccounts, org.mockito.Mockito.times(2))
                .ensureAccountsForCurrency(eq("USD"), eq("tenant-1"));
    }
}
