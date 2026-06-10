package io.nexuspay.ledger.application;

import io.nexuspay.ledger.application.port.LedgerAccountRepository;
import io.nexuspay.ledger.domain.AccountType;
import io.nexuspay.ledger.domain.LedgerAccount;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L-003: a single canonical account-id convention. Two different conventions
 * previously dead-lettered every non-USD payment. These tests pin the id helpers
 * and idempotent provisioning.
 */
class EnsureAccountsExistUseCaseTest {

    @Test
    void idHelpersFollowTheCanonicalConvention() {
        assertThat(EnsureAccountsExistUseCase.merchantReceivablesId("USD")).isEqualTo("la_merchant_recv_usd");
        assertThat(EnsureAccountsExistUseCase.customerLiabilityId("usd")).isEqualTo("la_customer_liab_usd");
        assertThat(EnsureAccountsExistUseCase.revenueId("JPY")).isEqualTo("la_revenue_jpy");
        assertThat(EnsureAccountsExistUseCase.processingFeesId("USD")).isEqualTo("la_processing_fees_usd");
        assertThat(EnsureAccountsExistUseCase.refundsId("USD")).isEqualTo("la_refunds_usd");
        assertThat(EnsureAccountsExistUseCase.fxClearingId("BHD")).isEqualTo("la_fx_clearing_bhd");
        assertThat(EnsureAccountsExistUseCase.accountId("revenue", "EUR")).isEqualTo("la_revenue_eur");
    }

    @Test
    void createsTheFiveDefaultAccountsWithCanonicalIdsWhenAbsent() {
        var repo = mock(LedgerAccountRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        var useCase = new EnsureAccountsExistUseCase(repo);

        useCase.ensureAccountsForCurrency("usd");

        ArgumentCaptor<LedgerAccount> saved = ArgumentCaptor.forClass(LedgerAccount.class);
        verify(repo, times(7)).save(saved.capture());
        Set<String> ids = saved.getAllValues().stream().map(LedgerAccount::getId).collect(Collectors.toSet());
        assertThat(ids).containsExactlyInAnyOrder(
                "la_merchant_recv_usd", "la_customer_liab_usd", "la_revenue_usd",
                "la_processing_fees_usd", "la_refunds_usd",
                "la_chargeback_reserve_usd", "la_chargeback_expense_usd");
    }

    @Test
    void isIdempotent_skipsAccountsThatAlreadyExist() {
        var repo = mock(LedgerAccountRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.of(
                new LedgerAccount("la_x", "x", AccountType.ASSET, "USD", 0L, 0L, "default",
                        Instant.now(), Instant.now())));
        var useCase = new EnsureAccountsExistUseCase(repo);

        useCase.ensureAccountsForCurrency("USD");

        verify(repo, never()).save(any());
    }

    @Test
    void propagatesTenantToCreatedAccounts() {
        var repo = mock(LedgerAccountRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        var useCase = new EnsureAccountsExistUseCase(repo);

        useCase.ensureAccountsForCurrency("usd", "tenant-x");

        ArgumentCaptor<LedgerAccount> saved = ArgumentCaptor.forClass(LedgerAccount.class);
        verify(repo, times(7)).save(saved.capture());
        assertThat(saved.getAllValues()).allMatch(a -> a.getTenantId().equals("tenant-x"));
    }
}
