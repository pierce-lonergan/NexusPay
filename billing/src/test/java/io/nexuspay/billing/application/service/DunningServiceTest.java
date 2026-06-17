package io.nexuspay.billing.application.service;

import io.nexuspay.billing.application.port.out.BillingOutboxPort;
import io.nexuspay.billing.application.port.out.InvoiceRepository;
import io.nexuspay.billing.application.port.out.PaymentPort;
import io.nexuspay.billing.application.port.out.SubscriptionRepository;
import io.nexuspay.billing.config.BillingConfig;
import io.nexuspay.billing.domain.DunningAttempt;
import io.nexuspay.billing.domain.Invoice;
import io.nexuspay.billing.domain.InvoiceLineItem;
import io.nexuspay.billing.domain.InvoiceStatus;
import io.nexuspay.billing.domain.Subscription;
import io.nexuspay.billing.domain.SubscriptionState;
import io.nexuspay.common.rls.TenantWorkRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dunning retry/exhaustion state machine — the most money- and security-sensitive
 * flow in billing. Asserts the success/recovery path, the no-double-bill obsolete
 * guard (never charges a settled invoice / dead subscription), the
 * retry-vs-exhaustion boundary (the documented {@code <=} off-by-one keeping the
 * grace attempt alive), and that one failing tenant does not abort the batch.
 *
 * <p>{@code processAttempt} is private, so it is exercised through
 * {@code processPendingAttempts}; {@link TenantWorkRunner#runInTenant} is stubbed
 * to run the work inline.</p>
 */
class DunningServiceTest {

    private InvoiceRepository invoiceRepository;
    private SubscriptionRepository subscriptionRepository;
    private PaymentPort paymentPort;
    private SmartRetryOptimizer smartRetryOptimizer;
    private BillingOutboxPort outboxPort;
    private TenantWorkRunner tenantWork;
    private BillingConfig.BillingProperties properties;
    private DunningService service;

    @BeforeEach
    void setUp() {
        invoiceRepository = mock(InvoiceRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        paymentPort = mock(PaymentPort.class);
        smartRetryOptimizer = mock(SmartRetryOptimizer.class);
        outboxPort = mock(BillingOutboxPort.class);
        tenantWork = mock(TenantWorkRunner.class);
        properties = new BillingConfig.BillingProperties(); // retrySchedule {1,3,5,7}, grace 3

        // optimize() is irrelevant to the assertions here — return a fixed future instant.
        when(smartRetryOptimizer.optimize(any(), any(), any()))
                .thenReturn(Instant.parse("2030-01-01T10:00:00Z"));

        // runInTenant executes the work inline so processAttempt actually runs.
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return null;
        }).when(tenantWork).runInTenant(anyString(), any(Runnable.class));

        service = new DunningService(invoiceRepository, subscriptionRepository, paymentPort,
                properties, smartRetryOptimizer, outboxPort, tenantWork);
    }

    // ---- fixtures ----

    private static Subscription pastDueSub() {
        Subscription s = new Subscription();
        s.setId("sub_1");
        s.setTenantId("t1");
        s.setCustomerId("cust_1");
        s.setPaymentMethodId("pm_1");
        s.setStatus(SubscriptionState.PAST_DUE);
        s.setCurrentPeriodStart(Instant.parse("2026-01-01T00:00:00Z"));
        s.setCurrentPeriodEnd(Instant.parse("2026-02-01T00:00:00Z"));
        return s;
    }

    private static Subscription activeSub() {
        Subscription s = pastDueSub();
        s.setStatus(SubscriptionState.ACTIVE);
        return s;
    }

    private static Invoice openInvoice() {
        Invoice inv = Invoice.createForSubscription("t1", "sub_1", "cust_1", "USD",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"));
        inv.addLineItem(new InvoiceLineItem("ili_1", inv.getId(), "t1", "charge", 5000, "USD",
                1, false, inv.getPeriodStart(), inv.getPeriodEnd()));
        inv.finalise(); // OPEN, amountDue 5000
        return inv;
    }

    private DunningAttempt attempt(int number) {
        DunningAttempt a = DunningAttempt.schedule("sub_1", "inv_1", "t1", number,
                Instant.parse("2026-01-05T10:00:00Z"));
        return a;
    }

    private void wirePending(DunningAttempt... attempts) {
        when(invoiceRepository.findPendingDunning(any(Instant.class), anyInt()))
                .thenReturn(List.of(attempts));
    }

    // ---- initiateDunning ----

    @Test
    void initiateDunningMarksPastDueSchedulesFirstAttemptAndPublishes() {
        Subscription sub = activeSub();
        Invoice inv = openInvoice();

        service.initiateDunning(sub, inv);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionState.PAST_DUE);
        verify(subscriptionRepository).save(sub);
        verify(invoiceRepository).saveDunningAttempt(any(DunningAttempt.class)); // attempt #1
        verify(outboxPort).publishEvent(eq("Subscription"), eq("sub_1"), eq("DunningInitiated"),
                any(Map.class), eq("t1"));
    }

    // ---- success / recovery ----

    @Test
    void successfulRetryMarksPaidReactivatesAndPublishesRecovered() {
        Subscription sub = pastDueSub();
        Invoice inv = openInvoice();
        when(subscriptionRepository.findById("sub_1")).thenReturn(Optional.of(sub));
        when(invoiceRepository.findById("inv_1")).thenReturn(Optional.of(inv));
        // production charges with invoice.getId() (a generated prefixed id), NOT the attempt's
        // "inv_1" reference; match the real id so the stub returns success (else null -> NPE).
        when(paymentPort.collectPayment(eq("t1"), eq("cust_1"), eq("pm_1"), eq(5000L),
                eq("USD"), anyString(), eq(inv.getId()), anyBoolean()))
                .thenReturn(PaymentPort.PaymentResult.success("pay_ok"));
        wirePending(attempt(1));

        int processed = service.processPendingAttempts();

        assertThat(processed).isEqualTo(1);
        assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(sub.getStatus()).isEqualTo(SubscriptionState.ACTIVE);
        verify(subscriptionRepository).save(sub);
        verify(invoiceRepository).save(inv);
        verify(outboxPort).publishEvent(eq("Subscription"), eq("sub_1"), eq("DunningRecovered"),
                any(Map.class), eq("t1"));
    }

    // ---- no-double-bill obsolete guard ----

    @Test
    void obsoleteWhenInvoiceAlreadyPaidNeverChargesAndMarksAttemptFailed() {
        Subscription sub = pastDueSub();
        Invoice inv = openInvoice();
        inv.markPaid("pay_prev"); // settled out-of-band -> status PAID, not OPEN
        when(subscriptionRepository.findById("sub_1")).thenReturn(Optional.of(sub));
        when(invoiceRepository.findById("inv_1")).thenReturn(Optional.of(inv));
        DunningAttempt a = attempt(2);
        wirePending(a);

        service.processPendingAttempts();

        verify(paymentPort, never()).collectPayment(anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), anyString(), anyBoolean());
        assertThat(a.getStatus()).isEqualTo(DunningAttempt.Status.FAILED);
        assertThat(a.getFailureReason()).startsWith("Obsolete");
        verify(invoiceRepository).saveDunningAttempt(a);
    }

    @Test
    void obsoleteWhenSubscriptionNoLongerPastDueNeverCharges() {
        Subscription sub = pastDueSub();
        sub.setStatus(SubscriptionState.CANCELED); // resurrected-from-dead guard
        Invoice inv = openInvoice();
        when(subscriptionRepository.findById("sub_1")).thenReturn(Optional.of(sub));
        when(invoiceRepository.findById("inv_1")).thenReturn(Optional.of(inv));
        DunningAttempt a = attempt(2);
        wirePending(a);

        service.processPendingAttempts();

        verify(paymentPort, never()).collectPayment(anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), anyString(), anyBoolean());
        assertThat(a.getStatus()).isEqualTo(DunningAttempt.Status.FAILED);
    }

    // ---- missing data / no payment method ----

    @Test
    void missingSubscriptionMarksFailedAndNeverCharges() {
        when(subscriptionRepository.findById("sub_1")).thenReturn(Optional.empty());
        when(invoiceRepository.findById("inv_1")).thenReturn(Optional.of(openInvoice()));
        DunningAttempt a = attempt(1);
        wirePending(a);

        service.processPendingAttempts();

        assertThat(a.getStatus()).isEqualTo(DunningAttempt.Status.FAILED);
        verify(paymentPort, never()).collectPayment(anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void missingInvoiceMarksFailedAndNeverCharges() {
        when(subscriptionRepository.findById("sub_1")).thenReturn(Optional.of(pastDueSub()));
        when(invoiceRepository.findById("inv_1")).thenReturn(Optional.empty());
        DunningAttempt a = attempt(1);
        wirePending(a);

        service.processPendingAttempts();

        assertThat(a.getStatus()).isEqualTo(DunningAttempt.Status.FAILED);
        verify(paymentPort, never()).collectPayment(anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void nullPaymentMethodMarksFailedAndNeverCharges() {
        Subscription sub = pastDueSub();
        sub.setPaymentMethodId(null);
        when(subscriptionRepository.findById("sub_1")).thenReturn(Optional.of(sub));
        when(invoiceRepository.findById("inv_1")).thenReturn(Optional.of(openInvoice()));
        DunningAttempt a = attempt(1);
        wirePending(a);

        service.processPendingAttempts();

        assertThat(a.getStatus()).isEqualTo(DunningAttempt.Status.FAILED);
        verify(paymentPort, never()).collectPayment(anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), anyString(), anyBoolean());
    }

    // ---- failure with retries left ----

    @Test
    void failureWithRetriesLeftSchedulesNextAndPublishesRetryFailed() {
        Subscription sub = pastDueSub();
        Invoice inv = openInvoice();
        when(subscriptionRepository.findById("sub_1")).thenReturn(Optional.of(sub));
        when(invoiceRepository.findById("inv_1")).thenReturn(Optional.of(inv));
        when(paymentPort.collectPayment(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(PaymentPort.PaymentResult.failure("declined"));
        wirePending(attempt(1)); // 1 <= 4 -> retries remain

        service.processPendingAttempts();

        // invoice/sub remain in dunning, NOT canceled.
        assertThat(sub.getStatus()).isEqualTo(SubscriptionState.PAST_DUE);
        assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.OPEN);
        verify(outboxPort).publishEvent(eq("Subscription"), eq("sub_1"), eq("DunningRetryFailed"),
                any(Map.class), eq("t1"));
        verify(outboxPort, never()).publishEvent(anyString(), anyString(), eq("DunningExhausted"),
                any(Map.class), anyString());
    }

    @Test
    void failureOnLastScheduledRetryStillSchedulesGraceAttempt() {
        // attemptNumber == retrySchedule.length (4). The documented "<=" keeps the
        // grace attempt alive; a "<" here would cancel prematurely. So this must
        // NOT cancel — it schedules attempt #5.
        Subscription sub = pastDueSub();
        Invoice inv = openInvoice();
        when(subscriptionRepository.findById("sub_1")).thenReturn(Optional.of(sub));
        when(invoiceRepository.findById("inv_1")).thenReturn(Optional.of(inv));
        when(paymentPort.collectPayment(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(PaymentPort.PaymentResult.failure("declined"));
        wirePending(attempt(4));

        service.processPendingAttempts();

        assertThat(sub.getStatus()).isEqualTo(SubscriptionState.PAST_DUE);
        verify(outboxPort).publishEvent(eq("Subscription"), eq("sub_1"), eq("DunningRetryFailed"),
                any(Map.class), eq("t1"));
        verify(outboxPort, never()).publishEvent(anyString(), anyString(), eq("DunningExhausted"),
                any(Map.class), anyString());
    }

    // ---- exhaustion boundary ----

    @Test
    void failureBeyondScheduleCancelsAndMarksUncollectible() {
        // attemptNumber 5 > retrySchedule.length (4) -> exhausted.
        Subscription sub = pastDueSub();
        Invoice inv = openInvoice();
        when(subscriptionRepository.findById("sub_1")).thenReturn(Optional.of(sub));
        when(invoiceRepository.findById("inv_1")).thenReturn(Optional.of(inv));
        when(paymentPort.collectPayment(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(PaymentPort.PaymentResult.failure("declined"));
        wirePending(attempt(5));

        service.processPendingAttempts();

        assertThat(sub.getStatus()).isEqualTo(SubscriptionState.CANCELED);
        assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.UNCOLLECTIBLE);
        verify(outboxPort).publishEvent(eq("Subscription"), eq("sub_1"), eq("DunningExhausted"),
                any(Map.class), eq("t1"));
        verify(outboxPort).publishEvent(eq("Subscription"), eq("sub_1"), eq("SubscriptionCanceled"),
                any(Map.class), eq("t1"));
    }

    // ---- batch resilience ----

    @Test
    void oneTenantFailingDoesNotAbortTheBatch() {
        DunningAttempt bad = attempt(1);
        bad.setTenantId("t-bad");
        DunningAttempt good = attempt(1);
        good.setTenantId("t-good");
        wirePending(bad, good);

        // The bad tenant's runInTenant throws; the good tenant's runs normally (no-op work).
        doThrow(new RuntimeException("rls denied")).when(tenantWork)
                .runInTenant(eq("t-bad"), any(Runnable.class));
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return null;
        }).when(tenantWork).runInTenant(eq("t-good"), any(Runnable.class));
        // good tenant's processAttempt needs lookups; missing-data path is fine (no charge).
        when(subscriptionRepository.findById("sub_1")).thenReturn(Optional.empty());
        when(invoiceRepository.findById("inv_1")).thenReturn(Optional.empty());

        int processed = service.processPendingAttempts();

        assertThat(processed).isEqualTo(1); // only the good tenant counted
        verify(tenantWork).runInTenant(eq("t-good"), any(Runnable.class));
    }

    // ---- DX-5a (MONEY-SAFETY): the durable test/live mode is threaded into the charge ----

    @Test
    void dunningRetryForTestSubscription_passesLiveFalseToCollectPayment() {
        // A TEST subscription (is_live=false). Dunning runs on the scheduler's SYSTEM thread where the
        // request-scoped PaymentMode is unset; the durable is_live MUST be threaded into collectPayment
        // so the gateway routes the retry to the mock, never the real PSP. Pre-DX-5a this charge would
        // have hit HyperSwitch (system thread + unset mode -> real).
        Subscription sub = pastDueSub();
        sub.setLive(false); // test-mode subscription
        Invoice inv = openInvoice();
        when(subscriptionRepository.findById("sub_1")).thenReturn(Optional.of(sub));
        when(invoiceRepository.findById("inv_1")).thenReturn(Optional.of(inv));
        when(paymentPort.collectPayment(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(PaymentPort.PaymentResult.failure("declined"));
        wirePending(attempt(1));

        service.processPendingAttempts();

        ArgumentCaptor<Boolean> live = ArgumentCaptor.forClass(Boolean.class);
        verify(paymentPort).collectPayment(eq("t1"), eq("cust_1"), eq("pm_1"), anyLong(),
                eq("USD"), anyString(), anyString(), live.capture());
        assertThat(live.getValue()).isFalse(); // routes to the mock, never the real PSP
    }

    @Test
    void dunningRetryForLiveSubscription_passesLiveTrueToCollectPayment() {
        // A LIVE subscription (is_live=true) keeps routing to the real PSP — the carve-out is intact.
        Subscription sub = pastDueSub();
        sub.setLive(true);
        Invoice inv = openInvoice();
        when(subscriptionRepository.findById("sub_1")).thenReturn(Optional.of(sub));
        when(invoiceRepository.findById("inv_1")).thenReturn(Optional.of(inv));
        when(paymentPort.collectPayment(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(PaymentPort.PaymentResult.failure("declined"));
        wirePending(attempt(1));

        service.processPendingAttempts();

        ArgumentCaptor<Boolean> live = ArgumentCaptor.forClass(Boolean.class);
        verify(paymentPort).collectPayment(eq("t1"), eq("cust_1"), eq("pm_1"), anyLong(),
                eq("USD"), anyString(), anyString(), live.capture());
        assertThat(live.getValue()).isTrue();
    }

    @Test
    void emptyPendingListProcessesNothing() {
        when(invoiceRepository.findPendingDunning(any(Instant.class), anyInt()))
                .thenReturn(List.of());

        int processed = service.processPendingAttempts();

        assertThat(processed).isEqualTo(0);
        verify(paymentPort, never()).collectPayment(anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), anyString(), anyBoolean());
    }
}
