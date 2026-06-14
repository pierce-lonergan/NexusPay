package io.nexuspay.ledger.application;

import io.nexuspay.common.exception.LedgerException;
import io.nexuspay.ledger.application.port.LedgerAccountRepository;
import io.nexuspay.ledger.domain.AccountType;
import io.nexuspay.ledger.domain.LedgerAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the balance read path. The behavior worth pinning is the
 * typed account-not-found failure (callers and the HTTP layer depend on a
 * LedgerException, not a null/NPE) plus delegation of the list queries.
 */
class GetBalanceUseCaseTest {

    private LedgerAccountRepository repo;
    private GetBalanceUseCase useCase;

    @BeforeEach
    void setUp() {
        repo = mock(LedgerAccountRepository.class);
        useCase = new GetBalanceUseCase(repo);
    }

    private LedgerAccount account(String id) {
        return new LedgerAccount(id, "name", AccountType.ASSET, "USD",
                1234L, 0L, "default", Instant.now(), Instant.now());
    }

    @Test
    void getBalance_returnsAccountWhenPresent() {
        LedgerAccount acct = account("la_merchant_recv_usd");
        when(repo.findById("la_merchant_recv_usd")).thenReturn(Optional.of(acct));

        LedgerAccount result = useCase.getBalance("la_merchant_recv_usd");

        assertThat(result).isSameAs(acct);
        assertThat(result.getId()).isEqualTo("la_merchant_recv_usd");
    }

    @Test
    void getBalance_throwsAccountNotFoundWhenMissing() {
        when(repo.findById("la_missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getBalance("la_missing"))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("la_missing")
                .satisfies(ex -> assertThat(((LedgerException) ex).getErrorCode()).isEqualTo("account_not_found"));
    }

    @Test
    void getAllBalances_delegatesAndReturnsListAsIs() {
        LedgerAccount a = account("la_a");
        LedgerAccount b = account("la_b");
        when(repo.findAllByTenantId("tenant-1")).thenReturn(List.of(a, b));

        List<LedgerAccount> result = useCase.getAllBalances("tenant-1");

        assertThat(result).containsExactly(a, b);
    }

    @Test
    void getAllBalances_returnsEmptyListUnchanged() {
        when(repo.findAllByTenantId("tenant-empty")).thenReturn(List.of());

        assertThat(useCase.getAllBalances("tenant-empty")).isEmpty();
    }

    @Test
    void getBalancesByCurrency_delegatesToFindAllByCurrency() {
        LedgerAccount a = account("la_a_usd");
        when(repo.findAllByCurrency("USD")).thenReturn(List.of(a));

        List<LedgerAccount> result = useCase.getBalancesByCurrency("USD");

        assertThat(result).containsExactly(a);
    }
}
