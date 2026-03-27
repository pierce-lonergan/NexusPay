package io.nexuspay.marketplace.application.service;

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

    @BeforeEach
    void setUp() {
        service = new PayoutService(repository, payoutExecution, eventPublisher);
    }

    @Test
    void createPayout_succeeds() {
        ConnectedAccount account = ConnectedAccount.create("tenant-1", "Test Biz", "t@test.com", "US", "USD");
        account.setPayoutMinimum(100);
        when(repository.findAccountById(account.getId())).thenReturn(Optional.of(account));
        when(repository.savePayout(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createPayout(new SchedulePayoutUseCase.CreatePayoutCommand(
                "tenant-1", account.getId(), 5000, "USD", PayoutMethod.BANK_TRANSFER, null));

        assertNotNull(result.payoutId());
        assertTrue(result.payoutId().startsWith("po_"));
        assertEquals(5000, result.amount());
        assertEquals(PayoutStatus.PENDING, result.status());
        verify(eventPublisher).publishEvent(eq("Payout"), any(), eq("PayoutCreated"), any(), eq("tenant-1"));
    }

    @Test
    void createPayout_rejectsBelowMinimum() {
        ConnectedAccount account = ConnectedAccount.create("tenant-1", "Test Biz", "t@test.com", "US", "USD");
        account.setPayoutMinimum(10000);
        when(repository.findAccountById(account.getId())).thenReturn(Optional.of(account));

        assertThrows(IllegalArgumentException.class, () ->
                service.createPayout(new SchedulePayoutUseCase.CreatePayoutCommand(
                        "tenant-1", account.getId(), 5000, "USD", PayoutMethod.BANK_TRANSFER, null)));
    }

    @Test
    void createPayout_rejectsUnknownAccount() {
        when(repository.findAccountById("ca_missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                service.createPayout(new SchedulePayoutUseCase.CreatePayoutCommand(
                        "tenant-1", "ca_missing", 5000, "USD", PayoutMethod.BANK_TRANSFER, null)));
    }

    @Test
    void processPendingPayouts_executesAndMarksPaid() {
        Payout payout = Payout.create("ca_acc1", "tenant-1", 5000, "USD", PayoutMethod.BANK_TRANSFER);
        payout.schedule(Instant.now().minusSeconds(60));
        when(repository.findPendingPayoutsDueBefore(any())).thenReturn(List.of(payout));
        when(repository.savePayout(any())).thenAnswer(inv -> inv.getArgument(0));
        when(payoutExecution.execute(any())).thenReturn(
                new PayoutExecutionPort.PayoutExecutionResult(true, "pex_ref123", null));

        service.processPendingPayouts();

        verify(payoutExecution).execute(any());
        verify(eventPublisher).publishEvent(eq("Payout"), any(), eq("PayoutPaid"), any(), eq("tenant-1"));
    }

    @Test
    void processPendingPayouts_handlesFailure() {
        Payout payout = Payout.create("ca_acc1", "tenant-1", 5000, "USD", PayoutMethod.BANK_TRANSFER);
        payout.schedule(Instant.now().minusSeconds(60));
        when(repository.findPendingPayoutsDueBefore(any())).thenReturn(List.of(payout));
        when(repository.savePayout(any())).thenAnswer(inv -> inv.getArgument(0));
        when(payoutExecution.execute(any())).thenReturn(
                new PayoutExecutionPort.PayoutExecutionResult(false, null, "Insufficient funds"));

        service.processPendingPayouts();

        verify(eventPublisher).publishEvent(eq("Payout"), any(), eq("PayoutFailed"), any(), eq("tenant-1"));
    }

    @Test
    void listPayouts_returnsByAccount() {
        Payout p1 = Payout.create("ca_acc1", "tenant-1", 1000, "USD", PayoutMethod.BANK_TRANSFER);
        Payout p2 = Payout.create("ca_acc1", "tenant-1", 2000, "USD", PayoutMethod.CARD_PUSH);
        when(repository.findPayoutsByAccountId("ca_acc1")).thenReturn(List.of(p1, p2));

        var results = service.listPayouts("tenant-1", "ca_acc1");
        assertEquals(2, results.size());
    }
}
