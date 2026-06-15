package io.nexuspay.marketplace.application.service;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.marketplace.application.port.in.SchedulePayoutUseCase;
import io.nexuspay.marketplace.application.port.out.MarketplaceEventPublisher;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.application.port.out.PayoutExecutionPort;
import io.nexuspay.marketplace.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PayoutService}.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@ExtendWith(MockitoExtension.class)
class PayoutServiceTest {

    @Mock private MarketplaceRepository repository;
    @Mock private PayoutExecutionPort payoutExecution;
    @Mock private MarketplaceEventPublisher eventPublisher;

    private PayoutService service;

    private static final String TENANT = "tenant-1";

    @BeforeEach
    void setUp() {
        service = new PayoutService(repository, payoutExecution, eventPublisher);
    }

    /** Builds an ACTIVE, KYC-verified account — the precondition for payouts. */
    private static ConnectedAccount activeAccount() {
        ConnectedAccount account = ConnectedAccount.create(TENANT, "Test Biz", "t@test.com", "US", "USD");
        account.setKycStatus(KycStatus.VERIFIED);
        account.setStatus(AccountState.ACTIVE);
        return account;
    }

    @Test
    void createPayout_succeeds() {
        ConnectedAccount account = activeAccount();
        account.setPayoutMinimum(100);
        // SEC-BATCH-1: referenced account is now loaded tenant-scoped (id + caller tenant).
        when(repository.findAccountById(account.getId(), TENANT)).thenReturn(Optional.of(account));
        when(repository.savePayout(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createPayout(new SchedulePayoutUseCase.CreatePayoutCommand(
                TENANT, account.getId(), 5000, "USD", PayoutMethod.BANK_TRANSFER, null));

        assertNotNull(result.payoutId());
        assertTrue(result.payoutId().startsWith("po_"));
        assertEquals(5000, result.amount());
        assertEquals(PayoutStatus.PENDING, result.status());
        verify(eventPublisher).publishEvent(eq("Payout"), any(), eq("PayoutCreated"), any(), eq(TENANT));
    }

    @Test
    void createPayout_foreignAccount_throwsNotFound() {
        // SEC-BATCH-1: account belongs to tenant-2; the tenant-scoped finder returns empty for the
        // tenant-1 caller, so the payout is rejected (404) — money cannot be misdirected.
        when(repository.findAccountById("ca_foreign", TENANT)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.createPayout(new SchedulePayoutUseCase.CreatePayoutCommand(
                        TENANT, "ca_foreign", 5000, "USD", PayoutMethod.BANK_TRANSFER, null)));
        verify(repository, never()).savePayout(any());
    }

    @Test
    void createPayout_rejectsBelowMinimum() {
        ConnectedAccount account = activeAccount();
        account.setPayoutMinimum(10000);
        when(repository.findAccountById(account.getId(), TENANT)).thenReturn(Optional.of(account));

        assertThrows(IllegalArgumentException.class, () ->
                service.createPayout(new SchedulePayoutUseCase.CreatePayoutCommand(
                        TENANT, account.getId(), 5000, "USD", PayoutMethod.BANK_TRANSFER, null)));
    }

    @Test
    void createPayout_rejectsInactiveAccount() {
        ConnectedAccount account = ConnectedAccount.create(TENANT, "Test Biz", "t@test.com", "US", "USD");
        account.setPayoutMinimum(100); // ONBOARDING, not yet activated
        when(repository.findAccountById(account.getId(), TENANT)).thenReturn(Optional.of(account));

        assertThrows(IllegalStateException.class, () ->
                service.createPayout(new SchedulePayoutUseCase.CreatePayoutCommand(
                        TENANT, account.getId(), 5000, "USD", PayoutMethod.BANK_TRANSFER, null)));
    }

    @Test
    void createPayout_rejectsUnknownAccount() {
        when(repository.findAccountById("ca_missing", TENANT)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.createPayout(new SchedulePayoutUseCase.CreatePayoutCommand(
                        TENANT, "ca_missing", 5000, "USD", PayoutMethod.BANK_TRANSFER, null)));
    }

    @Test
    void getPayout_succeeds() {
        Payout payout = Payout.create("ca_acc1", TENANT, 1000, "USD", PayoutMethod.BANK_TRANSFER);
        when(repository.findPayoutById(payout.getId(), TENANT)).thenReturn(Optional.of(payout));

        var result = service.getPayout(payout.getId(), TENANT);
        assertEquals(payout.getId(), result.payoutId());
    }

    @Test
    void getPayout_crossTenant_throwsNotFound() {
        // SEC-BATCH-1: caller tenant-1 requests a payout owned by tenant-2 → tenant-scoped finder
        // returns empty → 404 (same as truly-absent, no existence oracle).
        when(repository.findPayoutById("po_foreign", TENANT)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getPayout("po_foreign", TENANT));
    }

    @Test
    void processPendingPayouts_executesAndMarksPaid() {
        Payout payout = Payout.create("ca_acc1", TENANT, 5000, "USD", PayoutMethod.BANK_TRANSFER);
        payout.schedule(Instant.now().minusSeconds(60));
        when(repository.findPendingPayoutsDueBefore(any())).thenReturn(List.of(payout));
        when(repository.savePayout(any())).thenAnswer(inv -> inv.getArgument(0));
        when(payoutExecution.execute(any())).thenReturn(
                new PayoutExecutionPort.PayoutExecutionResult(true, "pex_ref123", null));

        service.processPendingPayouts();

        verify(payoutExecution).execute(any());
        verify(eventPublisher).publishEvent(eq("Payout"), any(), eq("PayoutPaid"), any(), eq(TENANT));
    }

    @Test
    void processPendingPayouts_handlesFailure() {
        Payout payout = Payout.create("ca_acc1", TENANT, 5000, "USD", PayoutMethod.BANK_TRANSFER);
        payout.schedule(Instant.now().minusSeconds(60));
        when(repository.findPendingPayoutsDueBefore(any())).thenReturn(List.of(payout));
        when(repository.savePayout(any())).thenAnswer(inv -> inv.getArgument(0));
        when(payoutExecution.execute(any())).thenReturn(
                new PayoutExecutionPort.PayoutExecutionResult(false, null, "Insufficient funds"));

        service.processPendingPayouts();

        verify(eventPublisher).publishEvent(eq("Payout"), any(), eq("PayoutFailed"), any(), eq(TENANT));
    }

    @Test
    void listPayouts_returnsByAccount() {
        Payout p1 = Payout.create("ca_acc1", TENANT, 1000, "USD", PayoutMethod.BANK_TRANSFER);
        Payout p2 = Payout.create("ca_acc1", TENANT, 2000, "USD", PayoutMethod.CARD_PUSH);
        // SEC-BATCH-1: list is now scoped to (accountId, caller tenant).
        when(repository.findPayoutsByAccountId("ca_acc1", TENANT)).thenReturn(List.of(p1, p2));

        var results = service.listPayouts(TENANT, "ca_acc1");
        assertEquals(2, results.size());
    }

    @Test
    void listPayouts_crossTenant_returnsEmpty() {
        // SEC-BATCH-1: a guessed foreign accountId returns nothing for the caller's tenant.
        when(repository.findPayoutsByAccountId("ca_foreign", TENANT)).thenReturn(List.of());

        var results = service.listPayouts(TENANT, "ca_foreign");
        assertTrue(results.isEmpty());
    }
}
