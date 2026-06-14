package io.nexuspay.billing.adapter.in.scheduler;

import io.nexuspay.billing.application.port.out.BillingOutboxPort;
import io.nexuspay.billing.application.port.out.ProductRepository;
import io.nexuspay.billing.application.port.out.SubscriptionRepository;
import io.nexuspay.billing.application.service.DunningService;
import io.nexuspay.billing.application.service.InvoiceGenerationService;
import io.nexuspay.common.rls.TenantWorkRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-001: the renewal/dunning crons must run their work ONLY through the
 * cross-instance lock, so a multi-replica deployment cannot double-charge.
 */
class RenewalSchedulerTest {

    private SubscriptionRepository subs;
    private SchedulerLock lock;
    private RenewalScheduler scheduler;

    @BeforeEach
    void setUp() {
        subs = mock(SubscriptionRepository.class);
        lock = mock(SchedulerLock.class);
        scheduler = new RenewalScheduler(subs, mock(ProductRepository.class),
                mock(InvoiceGenerationService.class), mock(DunningService.class),
                mock(BillingOutboxPort.class), lock, mock(TenantWorkRunner.class));
    }

    private void lockGrants(String name) {
        when(lock.runExclusively(eq(name), any(Duration.class), any(Runnable.class)))
                .thenAnswer(inv -> { inv.getArgument(2, Runnable.class).run(); return true; });
    }

    private void lockDenies(String name) {
        when(lock.runExclusively(eq(name), any(Duration.class), any(Runnable.class))).thenReturn(false);
    }

    @Test
    void processRenewals_runsWorkOnlyWhenLockGranted() {
        lockGrants("renewals");
        when(subs.findDueForRenewal(any(), anyInt())).thenReturn(List.of());

        scheduler.processRenewals();

        verify(subs).findDueForRenewal(any(), eq(500));
    }

    @Test
    void processRenewals_doesNothingWhenLockDenied() {
        lockDenies("renewals");

        scheduler.processRenewals();

        verify(subs, never()).findDueForRenewal(any(), anyInt());
    }

    @Test
    void processRenewals_acquiresTheRenewalsLock() {
        lockDenies("renewals");
        scheduler.processRenewals();
        verify(lock).runExclusively(eq("renewals"), any(Duration.class), any(Runnable.class));
    }

    @Test
    void processDunning_acquiresTheDunningLock() {
        lockDenies("dunning");
        scheduler.processDunning();
        verify(lock).runExclusively(eq("dunning"), any(Duration.class), any(Runnable.class));
    }
}
