package io.nexuspay.marketplace.adapter.in.scheduler;

import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.marketplace.application.port.out.PayoutExecutionPort;
import io.nexuspay.marketplace.application.port.out.PayoutExecutionPort.PayoutExecutionRequest;
import io.nexuspay.marketplace.application.port.out.PayoutExecutionPort.PayoutExecutionResult;
import io.nexuspay.marketplace.application.service.PayoutReconcileService;
import io.nexuspay.marketplace.domain.Payout;
import io.nexuspay.marketplace.domain.PayoutMethod;
import io.nexuspay.marketplace.domain.PayoutStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-25: the payout reconciler re-drives payouts stranded in PROCESSING (crashed between the SEC-11
 * PENDING->PROCESSING claim and the terminal disburse-result write) via the deterministic
 * {@code "payout-<id>"} idempotency key, so the PSP dedups — NO double-pay. A transient gateway failure
 * leaves the row PROCESSING (re-drivable, bumped attempt); a terminal PSP failure moves it to FAILED; a
 * success moves it to PAID. The lock fails CLOSED.
 *
 * <p>These tests use a faithful in-memory {@link PayoutReconcileService} double ({@link InMemoryPayouts})
 * that enforces the SAME guards the real conditional UPDATEs do — status='PROCESSING' gating, tenant
 * match, attempt increments, the stuck/exhausted finder filters and the backoff gate — rather than a
 * stub that ignores them (L-039/L-041). The real {@code PayoutReconciler} drives it unchanged.</p>
 *
 * <p>Test #1 ({@link #stuckProcessingPayoutOlderThanThresholdIsReconciledToPaid}) FAILS without the
 * reconciler: nothing else re-selects a PROCESSING row, so the payout stays PROCESSING forever.</p>
 */
class PayoutReconcilerTest {

    private InMemoryPayouts payouts;
    private PayoutExecutionPort gateway;
    private MarketplaceSchedulerLock lock;
    private TenantWorkRunner tenantWork;
    private PayoutReconciler reconciler;

    private static final int MAX_ATTEMPTS = 5;
    private static final int BATCH = 100;
    private static final long STUCK_THRESHOLD_MS = 300_000; // 5 min

    @BeforeEach
    void setUp() {
        payouts = new InMemoryPayouts();
        gateway = mock(PayoutExecutionPort.class);
        lock = mock(MarketplaceSchedulerLock.class);
        tenantWork = directTenantWork();
        reconciler = new PayoutReconciler(payouts.asService(gateway), lock, tenantWork,
                MAX_ATTEMPTS, BATCH, STUCK_THRESHOLD_MS);
        // By default the lock grants and runs the cycle inline.
        when(lock.runExclusively(any(), any(Duration.class), any(Runnable.class)))
                .thenAnswer(inv -> { inv.getArgument(2, Runnable.class).run(); return true; });
    }

    // ---- 1. Happy re-drive (FAILS without the reconciler) --------------------------------------

    @Test
    void stuckProcessingPayoutOlderThanThresholdIsReconciledToPaid() {
        payouts.seedProcessing("po_1", "t1", minutesAgo(10));
        gatewaySucceeds();

        reconciler.reconcileStuckPayouts();

        var row = payouts.get("po_1");
        assertThat(row.getStatus()).as("PROCESSING -> PAID after a successful re-drive").isEqualTo(PayoutStatus.PAID);
        assertThat(row.getPaidAt()).as("paid_at stamped").isNotNull();
        assertThat(row.getExternalReference()).isNotBlank();
        verify(gateway).execute(any(PayoutExecutionRequest.class));
    }

    // ---- 2. Terminal PSP failure -> FAILED -----------------------------------------------------

    @Test
    void terminalPspFailureMovesToFailed() {
        payouts.seedProcessing("po_1", "t1", minutesAgo(10));
        when(gateway.execute(any())).thenReturn(new PayoutExecutionResult(false, null, "account closed"));

        reconciler.reconcileStuckPayouts();

        var row = payouts.get("po_1");
        assertThat(row.getStatus()).as("terminal PSP failure -> FAILED").isEqualTo(PayoutStatus.FAILED);
        assertThat(row.getFailureReason()).contains("account closed");
        assertThat(row.getPaidAt()).as("never marked PAID").isNull();
    }

    // ---- 3. Not yet stuck (below threshold) is not touched -------------------------------------

    @Test
    void notYetStuck_belowThreshold_isNotTouched() {
        payouts.seedProcessing("po_fresh", "t1", Instant.now().minusSeconds(30)); // < 5 min
        gatewaySucceeds();

        reconciler.reconcileStuckPayouts();

        assertThat(payouts.get("po_fresh").getStatus()).isEqualTo(PayoutStatus.PROCESSING);
        verify(gateway, never()).execute(any()); // no race with a live disburse
    }

    // ---- 4. Every re-drive reuses the deterministic key ----------------------------------------

    @Test
    void everyReDriveReusesDeterministicIdempotencyKey() {
        payouts.seedProcessing("po_42", "t1", minutesAgo(10));
        // cycle 1: gateway throws (transient). cycle 2 (after backoff cleared): succeeds.
        when(gateway.execute(any()))
                .thenThrow(new RuntimeException("psp unreachable"))
                .thenReturn(new PayoutExecutionResult(true, "pex_ok", null));

        reconciler.reconcileStuckPayouts();      // attempt 1 (fail)
        payouts.clearBackoff("po_42");           // simulate clock past the gate
        reconciler.reconcileStuckPayouts();      // attempt 2 (succeed)

        ArgumentCaptor<PayoutExecutionRequest> cap = ArgumentCaptor.forClass(PayoutExecutionRequest.class);
        verify(gateway, times(2)).execute(cap.capture());
        assertThat(cap.getAllValues()).extracting(PayoutExecutionRequest::idempotencyKey)
                .as("re-drive must reuse the deterministic key on EVERY attempt — never randomize")
                .containsExactly("payout-po_42", "payout-po_42");
        assertThat(payouts.get("po_42").getStatus()).isEqualTo(PayoutStatus.PAID);
    }

    // ---- 5. No double-pay: PAID row removed from discovery; crash-redrive reuses same key ------

    @Test
    void reconciledPayoutNeverDisbursesTwice() {
        payouts.seedProcessing("po_1", "t1", minutesAgo(10));
        gatewaySucceeds();

        reconciler.reconcileStuckPayouts(); // cycle 1: success -> PAID
        reconciler.reconcileStuckPayouts(); // cycle 2: PAID is not PROCESSING -> not discovered

        verify(gateway, times(1)).execute(any());
        assertThat(payouts.get("po_1").getStatus()).isEqualTo(PayoutStatus.PAID);
    }

    @Test
    void redriveDurability_pspSuccessButMarkLost_reDrivesSameKeyNextCycleNoDoublePay() {
        // Process death AFTER the PSP disbursed but BEFORE the terminal mark committed: swallow the
        // first markPaid, then behave normally. The re-drive re-sends the IDENTICAL key; the modelled
        // PSP dedups, so it remains ONE logical payment.
        payouts.seedProcessing("po_crash", "t1", minutesAgo(10));
        gatewaySucceeds();
        payouts.dropNextMark("po_crash");

        reconciler.reconcileStuckPayouts(); // PSP succeeds, mark "lost"
        assertThat(payouts.get("po_crash").getStatus()).as("mark not yet applied").isEqualTo(PayoutStatus.PROCESSING);

        reconciler.reconcileStuckPayouts(); // re-drive: same key, mark now applied
        assertThat(payouts.get("po_crash").getStatus()).isEqualTo(PayoutStatus.PAID);

        ArgumentCaptor<PayoutExecutionRequest> cap = ArgumentCaptor.forClass(PayoutExecutionRequest.class);
        verify(gateway, times(2)).execute(cap.capture());
        assertThat(cap.getAllValues()).extracting(PayoutExecutionRequest::idempotencyKey)
                .containsExactly("payout-po_crash", "payout-po_crash");
    }

    // ---- 6. Transient failure leaves it PROCESSING and re-drivable -----------------------------

    @Test
    void transientGatewayFailureLeavesRowProcessingAndReDrivable() {
        payouts.seedProcessing("po_1", "t1", minutesAgo(10));
        when(gateway.execute(any())).thenThrow(new RuntimeException("circuit breaker open"));

        Instant before = Instant.now();
        reconciler.reconcileStuckPayouts();

        var row = payouts.get("po_1");
        assertThat(row.getStatus()).as("transient failure does NOT finalize").isEqualTo(PayoutStatus.PROCESSING);
        assertThat(row.getReconcileAttempts()).isEqualTo(1);
        assertThat(row.getLastReconcileError()).isNotBlank();
        assertThat(row.getNextReconcileAt()).isAfter(before.plus(Duration.ofMinutes(1)));
        // Still in the re-drivable set once the backoff elapses.
        List<Payout> next = payouts.asService(gateway).findStuckProcessing(
                minutesAgo(0), row.getNextReconcileAt().plusSeconds(1), MAX_ATTEMPTS, BATCH);
        assertThat(next).extracting(Payout::getId).contains("po_1");
    }

    // ---- 7. Backoff grows exponentially --------------------------------------------------------

    @Test
    void backoffGrowsExponentiallyWithAttempts() {
        // attempts already 3 -> next attempt index 4 -> backoff = min(2^4, 60) = 16 min.
        payouts.seedProcessing("po_bo", "t1", minutesAgo(10));
        payouts.setAttempts("po_bo", 3);
        when(gateway.execute(any())).thenThrow(new RuntimeException("boom"));

        Instant before = Instant.now();
        reconciler.reconcileStuckPayouts();

        var row = payouts.get("po_bo");
        assertThat(row.getReconcileAttempts()).isEqualTo(4);
        long gapMin = Duration.between(before, row.getNextReconcileAt()).toMinutes();
        assertThat(gapMin).isBetween(14L, 16L);
    }

    // ---- 8. Exhausted row not re-driven but surfaced -------------------------------------------

    @Test
    void exhaustedRowNotReDrivenButSurfacedToOperator() {
        payouts.seedProcessing("po_max", "t1", minutesAgo(10));
        payouts.setAttempts("po_max", MAX_ATTEMPTS); // == max -> excluded from finder

        reconciler.reconcileStuckPayouts();
        assertThat(payouts.get("po_max").getStatus()).isEqualTo(PayoutStatus.PROCESSING);
        verify(gateway, never()).execute(any());

        List<Payout> exhausted = payouts.asService(gateway).findExhaustedProcessing(MAX_ATTEMPTS);
        assertThat(exhausted).extracting(Payout::getId).containsExactly("po_max");
    }

    // ---- 9. Lock fails closed ------------------------------------------------------------------

    @Test
    void lockFailClosed_skipsCycle_noReDrive() {
        payouts.seedProcessing("po_1", "t1", minutesAgo(10));
        when(lock.runExclusively(any(), any(Duration.class), any(Runnable.class))).thenReturn(false);

        reconciler.reconcileStuckPayouts();

        verify(gateway, never()).execute(any());
        var row = payouts.get("po_1");
        assertThat(row.getStatus()).isEqualTo(PayoutStatus.PROCESSING);
        assertThat(row.getReconcileAttempts()).isZero();
    }

    // ---- 10. Lock names ------------------------------------------------------------------------

    @Test
    void cycleAlwaysRunsUnderThePayoutReconcileLock() {
        when(lock.runExclusively(any(), any(Duration.class), any(Runnable.class))).thenReturn(false);
        reconciler.reconcileStuckPayouts();
        verify(lock).runExclusively(eq("payout-reconcile"), any(Duration.class), any(Runnable.class));
    }

    @Test
    void signalRunsUnderDistinctLock() {
        when(lock.runExclusively(any(), any(Duration.class), any(Runnable.class))).thenReturn(false);
        reconciler.signalExhaustedPayouts();
        verify(lock).runExclusively(eq("payout-reconcile-signal"), any(Duration.class), any(Runnable.class));
    }

    @Test
    void signalLockFailClosed_doesNotQuery() {
        PayoutReconcileService svc = payouts.asService(gateway);
        reconciler = new PayoutReconciler(svc, lock, tenantWork, MAX_ATTEMPTS, BATCH, STUCK_THRESHOLD_MS);
        payouts.seedProcessing("po_max", "t1", minutesAgo(10));
        payouts.setAttempts("po_max", MAX_ATTEMPTS);
        when(lock.runExclusively(any(), any(Duration.class), any(Runnable.class))).thenReturn(false);

        reconciler.signalExhaustedPayouts();

        verify(svc, never()).findExhaustedProcessing(anyInt());
    }

    // ---- 11. Per-item tenant binding -----------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void eachReDriveIsBoundToTheRowsOwnTenant() {
        payouts.seedProcessing("po_a", "tenantA", minutesAgo(10));
        payouts.seedProcessing("po_b", "tenantB", minutesAgo(10));
        gatewaySucceeds();
        TenantWorkRunner mockWork = mock(TenantWorkRunner.class);
        when(mockWork.callInTenant(anyString(), any(Supplier.class)))
                .thenAnswer(inv -> inv.getArgument(1, Supplier.class).get());
        reconciler = new PayoutReconciler(payouts.asService(gateway), lock, mockWork,
                MAX_ATTEMPTS, BATCH, STUCK_THRESHOLD_MS);
        when(lock.runExclusively(any(), any(Duration.class), any(Runnable.class)))
                .thenAnswer(inv -> { inv.getArgument(2, Runnable.class).run(); return true; });

        reconciler.reconcileStuckPayouts();

        verify(mockWork).callInTenant(eq("tenantA"), any(Supplier.class));
        verify(mockWork).callInTenant(eq("tenantB"), any(Supplier.class));
        assertThat(payouts.get("po_a").getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(payouts.get("po_b").getStatus()).isEqualTo(PayoutStatus.PAID);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void gatewaySucceeds() {
        when(gateway.execute(any())).thenAnswer(inv -> {
            PayoutExecutionRequest req = inv.getArgument(0);
            // Deterministic ref from key (models PSP dedup).
            return new PayoutExecutionResult(true, "pex_" + req.idempotencyKey(), null);
        });
    }

    private static Instant minutesAgo(long m) {
        return Instant.now().minus(Duration.ofMinutes(m));
    }

    /** callInTenant that runs the supplier inline (the dormant-mode behavior of TenantWorkRunner). */
    private static TenantWorkRunner directTenantWork() {
        return new TenantWorkRunner() {
            @Override public void runInTenant(String tenantId, Runnable work) { work.run(); }
            @Override public <T> T callInTenant(String tenantId, Supplier<T> work) { return work.get(); }
            @Override public void bindTenant(String tenantId, Runnable work) { work.run(); }
        };
    }

    /**
     * Faithful in-memory stand-in for {@link PayoutReconcileService}. Enforces the SAME preconditions
     * the real conditional UPDATEs do: status='PROCESSING' gating on every transition, tenant match,
     * attempt increments, the stuck/exhausted finder filters and the backoff gate. The redrive()
     * delegates to the supplied (mocked) gateway with the REAL deterministic key, so the key invariant
     * is exercised end-to-end. It does NOT ignore the guards (L-039/L-041).
     */
    static final class InMemoryPayouts {
        private final Map<String, Payout> rows = new LinkedHashMap<>();
        private String dropMarkFor;
        private int markDrops;

        void seedProcessing(String id, String tenant, Instant processingSince) {
            Payout p = new Payout();
            p.setId(id);
            p.setConnectedAccountId("ca_" + id);
            p.setTenantId(tenant);
            p.setAmount(5000);
            p.setCurrency("USD");
            p.setMethod(PayoutMethod.BANK_TRANSFER);
            p.setStatus(PayoutStatus.PROCESSING);
            p.setProcessingSince(processingSince);
            p.setCreatedAt(processingSince);
            rows.put(id, p);
        }

        void setAttempts(String id, int n) { rows.get(id).setReconcileAttempts(n); }
        void clearBackoff(String id) { rows.get(id).setNextReconcileAt(null); }
        void dropNextMark(String id) { this.dropMarkFor = id; this.markDrops = 1; }

        Payout get(String id) { return rows.get(id); }

        PayoutReconcileService asService(PayoutExecutionPort gateway) {
            PayoutReconcileService svc = mock(PayoutReconcileService.class);

            when(svc.findStuckProcessing(any(Instant.class), any(Instant.class), anyInt(), anyInt()))
                    .thenAnswer(inv -> {
                        Instant cutoff = inv.getArgument(0);
                        Instant now = inv.getArgument(1);
                        int max = inv.getArgument(2);
                        int batch = inv.getArgument(3);
                        List<Payout> out = new ArrayList<>();
                        for (Payout r : rows.values()) {
                            Instant since = r.getProcessingSince() != null
                                    ? r.getProcessingSince() : Instant.EPOCH;
                            boolean due = r.getNextReconcileAt() == null
                                    || !r.getNextReconcileAt().isAfter(now);
                            if (r.getStatus() == PayoutStatus.PROCESSING
                                    && since.isBefore(cutoff)
                                    && r.getReconcileAttempts() < max
                                    && due) {
                                out.add(r);
                            }
                            if (out.size() >= batch) break;
                        }
                        return out;
                    });

            when(svc.findExhaustedProcessing(anyInt())).thenAnswer(inv -> {
                int max = inv.getArgument(0);
                List<Payout> out = new ArrayList<>();
                for (Payout r : rows.values()) {
                    if (r.getStatus() == PayoutStatus.PROCESSING && r.getReconcileAttempts() >= max) {
                        out.add(r);
                    }
                }
                return out;
            });

            when(svc.reloadStuckForUpdate(anyString())).thenAnswer(inv -> {
                Payout r = rows.get(inv.<String>getArgument(0));
                if (r == null || r.getStatus() != PayoutStatus.PROCESSING) return Optional.empty();
                return Optional.of(r);
            });

            // Real gateway call with the REAL deterministic key (exercises the key invariant).
            when(svc.redrive(any(Payout.class))).thenAnswer(inv -> {
                Payout p = inv.getArgument(0);
                return gateway.execute(new PayoutExecutionRequest(
                        p.getId(), p.getConnectedAccountId(), p.getAmount(), p.getCurrency(),
                        p.getMethod(), Payout.idempotencyKey(p.getId())));
            });

            when(svc.markPaid(anyString(), anyString(), anyString())).thenAnswer(inv -> {
                String id = inv.getArgument(0);
                String tenant = inv.getArgument(1);
                String ref = inv.getArgument(2);
                // Simulate a lost terminal write (crash before commit) exactly once for one row.
                if (id.equals(dropMarkFor) && markDrops > 0) {
                    markDrops--;
                    return false;
                }
                Payout r = rows.get(id);
                // Real semantics: conditional on PROCESSING AND tenant match.
                if (r == null || r.getStatus() != PayoutStatus.PROCESSING
                        || !r.getTenantId().equals(tenant)) {
                    return false;
                }
                r.markPaid(ref);
                r.setLastReconcileError(null);
                return true;
            });

            when(svc.markFailed(anyString(), anyString(), anyString())).thenAnswer(inv -> {
                String id = inv.getArgument(0);
                String tenant = inv.getArgument(1);
                String reason = inv.getArgument(2);
                Payout r = rows.get(id);
                if (r == null || r.getStatus() != PayoutStatus.PROCESSING
                        || !r.getTenantId().equals(tenant)) {
                    return false;
                }
                r.markFailed(reason);
                return true;
            });

            org.mockito.Mockito.doAnswer(inv -> {
                String id = inv.getArgument(0);
                String tenant = inv.getArgument(1);
                Instant next = inv.getArgument(2);
                String err = inv.getArgument(3);
                Payout r = rows.get(id);
                // Real semantics: guarded on PROCESSING AND tenant match; LEAVES status PROCESSING.
                if (r != null && r.getStatus() == PayoutStatus.PROCESSING
                        && r.getTenantId().equals(tenant)) {
                    r.setReconcileAttempts(r.getReconcileAttempts() + 1);
                    r.setNextReconcileAt(next);
                    r.setLastReconcileError(err);
                }
                return null;
            }).when(svc).recordFailure(anyString(), anyString(), any(Instant.class), anyString());

            return svc;
        }
    }
}
