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
        // GAP-063 marketplace accounts
        assertThat(EnsureAccountsExistUseCase.platformClearingId("USD")).isEqualTo("la_platform_clearing_usd");
        assertThat(EnsureAccountsExistUseCase.connectedPayableId("usd")).isEqualTo("la_connected_payable_usd");
        assertThat(EnsureAccountsExistUseCase.platformFeeRevenueId("EUR")).isEqualTo("la_platform_fee_revenue_eur");
        // GAP-069 b2b accounts
        assertThat(EnsureAccountsExistUseCase.accountsPayableId("USD")).isEqualTo("la_accounts_payable_usd");
        assertThat(EnsureAccountsExistUseCase.cashClearingId("USD")).isEqualTo("la_cash_clearing_usd");
        assertThat(EnsureAccountsExistUseCase.vendorPayableId("JPY")).isEqualTo("la_vendor_payable_jpy");
        assertThat(EnsureAccountsExistUseCase.vendorExpenseId("USD")).isEqualTo("la_vendor_expense_usd");
    }

    @Test
    void createsTheDefaultAccountsWithCanonicalIdsWhenAbsent() {
        var repo = mock(LedgerAccountRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        var useCase = new EnsureAccountsExistUseCase(repo);

        useCase.ensureAccountsForCurrency("usd");

        ArgumentCaptor<LedgerAccount> saved = ArgumentCaptor.forClass(LedgerAccount.class);
        verify(repo, times(14)).save(saved.capture());
        Set<String> ids = saved.getAllValues().stream().map(LedgerAccount::getId).collect(Collectors.toSet());
        assertThat(ids).containsExactlyInAnyOrder(
                "la_merchant_recv_usd", "la_customer_liab_usd", "la_revenue_usd",
                "la_processing_fees_usd", "la_refunds_usd",
                "la_chargeback_reserve_usd", "la_chargeback_expense_usd",
                // GAP-063 marketplace split-payment accounts
                "la_platform_clearing_usd", "la_connected_payable_usd", "la_platform_fee_revenue_usd",
                // GAP-069 b2b accounts
                "la_accounts_payable_usd", "la_cash_clearing_usd",
                "la_vendor_payable_usd", "la_vendor_expense_usd");
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
    void propagatesTenantToCreatedAccounts_exceptPlatformSharedSingletons() {
        var repo = mock(LedgerAccountRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        var useCase = new EnsureAccountsExistUseCase(repo);

        useCase.ensureAccountsForCurrency("usd", "tenant-x");

        ArgumentCaptor<LedgerAccount> saved = ArgumentCaptor.forClass(LedgerAccount.class);
        verify(repo, times(14)).save(saved.capture());
        // Legacy per-flow accounts keep the caller's tenant stamp (SEC-24 chargeback precedent).
        assertThat(saved.getAllValues().stream()
                .filter(a -> !PLATFORM_SHARED_IDS.contains(a.getId())))
                .isNotEmpty()
                .allMatch(a -> a.getTenantId().equals("tenant-x"));
        // Platform-shared singletons are NEVER caller-tenant-stamped (see the test below).
        assertThat(saved.getAllValues().stream()
                .filter(a -> PLATFORM_SHARED_IDS.contains(a.getId())))
                .hasSize(7)
                .allMatch(a -> a.getTenantId().equals(EnsureAccountsExistUseCase.DEFAULT_TENANT));
    }

    /** The 7 per-currency singletons that aggregate money across ALL tenants (GAP-063 + GAP-069). */
    private static final Set<String> PLATFORM_SHARED_IDS = Set.of(
            "la_platform_clearing_usd", "la_connected_payable_usd", "la_platform_fee_revenue_usd",
            "la_accounts_payable_usd", "la_cash_clearing_usd",
            "la_vendor_payable_usd", "la_vendor_expense_usd");

    @Test
    void platformSharedAccounts_areNeverStampedWithACallerTenant_noCrossTenantBalanceExposure() {
        // WAVE1 BLOCKER fix pin: the GAP-063/GAP-069 account ids have NO tenant component, so one row
        // per (account, currency) aggregates EVERY tenant's postings. First-writer-wins tenant
        // stamping would surface that aggregate to one tenant's GET /v1/ledger/accounts
        // (findAllByTenantId). They must be stamped DEFAULT_TENANT regardless of the caller — from
        // EVERY entry point that can create them (marketplace, b2b, dispute, payment consumer).
        var repo = mock(LedgerAccountRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        var useCase = new EnsureAccountsExistUseCase(repo);

        useCase.ensureAccountsForCurrency("USD", "first-writer-tenant");

        ArgumentCaptor<LedgerAccount> saved = ArgumentCaptor.forClass(LedgerAccount.class);
        verify(repo, times(14)).save(saved.capture());
        assertThat(saved.getAllValues().stream()
                .filter(a -> PLATFORM_SHARED_IDS.contains(a.getId())))
                .as("platform-shared singletons must be invisible to real tenants' balance reads")
                .hasSize(7)
                .allMatch(a -> a.getTenantId().equals(EnsureAccountsExistUseCase.DEFAULT_TENANT));
    }
}
