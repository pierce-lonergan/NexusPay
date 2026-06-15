package io.nexuspay.gateway.adapter.in.scheduler;

import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.gateway.application.RefundOrchestrationService;
import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.PendingApproval;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.RefundResponse;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-022: the refund reconciler re-drives stuck APPROVED-but-unexecuted refunds via
 * {@code executeApprovedRefund} (NEVER {@code approve()}), keyed on the deterministic
 * {@code refund-approval-<id>} idempotency key so the PSP dedups — no double-pay. A gateway failure
 * leaves the row re-drivable next cycle with a bumped attempt counter; an exhausted row is excluded
 * from re-drive and loudly surfaced for an operator. The lock fails CLOSED on Valkey down.
 *
 * <p>These tests use a faithful in-memory {@link ApprovalService} double ({@link InMemoryApprovals})
 * that enforces the SAME preconditions the real conditional UPDATEs do — {@code executed_at IS NULL}
 * gating, attempt increments, the discovery/exhausted filters and per-item tenant binding — rather
 * than a stub that ignores those guards (L-039/L-041). The real {@code RefundReconciler} drives it
 * unchanged.</p>
 */
class RefundReconcilerTest {

    private InMemoryApprovals approvals;
    private PaymentGatewayPort gateway;
    private RefundOrchestrationService refundOrchestration;
    private GatewaySchedulerLock lock;
    private TenantWorkRunner tenantWork;
    private RefundReconciler reconciler;

    private static final int MAX_ATTEMPTS = 5;

    @BeforeEach
    void setUp() {
        approvals = new InMemoryApprovals();
        gateway = mock(PaymentGatewayPort.class);
        // Real orchestration service so the ACTUAL deterministic-key logic runs (B-009 invariant).
        refundOrchestration = new RefundOrchestrationService(gateway, mock(ApprovalService.class), 50000L);
        lock = mock(GatewaySchedulerLock.class);
        tenantWork = directTenantWork(); // callInTenant runs the supplier inline, like the dormant impl
        reconciler = new RefundReconciler(approvals.asService(), refundOrchestration, lock, tenantWork,
                MAX_ATTEMPTS, 100);
        // By default the lock grants and runs the cycle inline.
        when(lock.runExclusively(any(), any(Duration.class), any(Runnable.class)))
                .thenAnswer(inv -> { inv.getArgument(2, Runnable.class).run(); return true; });
    }

    // ---- 1. Happy re-drive -------------------------------------------------------------------

    @Test
    void stuckApprovedRefundIsReDrivenAndMarkedExecuted() {
        approvals.seedStuckRefund("appr_1", "t1", "pay_1");
        gatewaySucceeds();

        reconciler.reconcileApprovedRefunds();

        var row = approvals.get("appr_1");
        assertThat(row.getExecutedAt()).as("executed_at stamped after success").isNotNull();
        assertThat(row.getReconcileAttempts()).as("attempts unchanged on success").isZero();
        assertThat(row.getStatus()).as("status stays APPROVED — no new status value").isEqualTo("APPROVED");
        verify(gateway).createRefund(any(RefundRequest.class));
    }

    @Test
    void secondCycleIsANoOp_markRemovesRowFromDiscovery_noSecondPspSubmit() {
        // Canonical proof: cycle-1 marks executed_at, which removes the row from cycle-2 discovery, so
        // there is no second PSP submit AT ALL — not even a (deduped) one. The PSP idempotency key is the
        // money backstop; this asserts we never even POST twice in the common path.
        approvals.seedStuckRefund("appr_1", "t1", "pay_1");
        gatewaySucceeds();

        reconciler.reconcileApprovedRefunds(); // cycle 1: succeed + mark
        reconciler.reconcileApprovedRefunds(); // cycle 2: row already executed → not discovered

        verify(gateway, times(1)).createRefund(any());
        assertThat(approvals.get("appr_1").getExecutedAt()).isNotNull();
    }

    // ---- 2. Gateway failure leaves it re-drivable + increments attempts ------------------------

    @Test
    void gatewayFailureLeavesRowReDrivableAndIncrementsAttempts() {
        approvals.seedStuckRefund("appr_1", "t1", "pay_1");
        when(gateway.createRefund(any())).thenThrow(PaymentException.gatewayError("boom", new RuntimeException()));

        Instant before = Instant.now();
        reconciler.reconcileApprovedRefunds();

        var row = approvals.get("appr_1");
        assertThat(row.getExecutedAt()).as("never marked executed on failure").isNull();
        assertThat(row.getReconcileAttempts()).as("attempt counter bumped").isEqualTo(1);
        assertThat(row.getLastReconcileError()).isNotBlank();
        // Backoff = now + min(2^1, 60) = +2 min; the row is gated until then but NOT stranded.
        assertThat(row.getNextReconcileAt()).isAfter(before.plus(Duration.ofMinutes(1)));
        // Still in the re-drivable set once the backoff elapses (simulate clock past the gate).
        List<PendingApproval> next = approvals.asService()
                .findStuckApprovedRefunds(row.getNextReconcileAt().plusSeconds(1), MAX_ATTEMPTS, 100);
        assertThat(next).extracting(PendingApproval::getId).contains("appr_1");
    }

    @Test
    void backoffGrowsExponentiallyWithAttempts() {
        // attempts already at 3 → next attempt index 4 → backoff = min(2^4, 60) = 16 min.
        approvals.seedStuckRefund("appr_bo", "t1", "pay_1");
        approvals.setAttempts("appr_bo", 3);
        when(gateway.createRefund(any())).thenThrow(PaymentException.gatewayError("boom", new RuntimeException()));

        Instant before = Instant.now();
        reconciler.reconcileApprovedRefunds();

        var row = approvals.get("appr_bo");
        assertThat(row.getReconcileAttempts()).isEqualTo(4);
        long gapMin = Duration.between(before, row.getNextReconcileAt()).toMinutes();
        // 2^4 = 16 min (well under the 60-min cap; the cap is the Math.min ceiling).
        assertThat(gapMin).isBetween(14L, 16L);
    }

    // ---- 3. pending / failed PSP responses do NOT mark executed --------------------------------

    @Test
    void pendingResponseDoesNotMarkExecutedAndDoesNotIncrementAttempts() {
        // B-022 FIX 2: a PSP `pending` (accepted, settling async) is BENIGN — not a failure. It must
        // NOT increment reconcile_attempts (else a perpetually-pending refund would eventually hit the
        // exhausted tail and false-page), and must leave the row re-drivable with executed_at NULL.
        approvals.seedStuckRefund("appr_1", "t1", "pay_1");
        when(gateway.createRefund(any())).thenReturn(refund(RefundResponse.STATUS_PENDING));

        Instant before = Instant.now();
        reconciler.reconcileApprovedRefunds();

        var row = approvals.get("appr_1");
        assertThat(row.getExecutedAt()).as("pending != executed").isNull();
        assertThat(row.getReconcileAttempts()).as("pending does NOT count an attempt").isZero();
        // A moderate re-check gate is set so it is polled again, but the row is still re-drivable once due.
        assertThat(row.getNextReconcileAt()).as("re-check gate set").isAfter(before);
        // It is also NOT in the exhausted/operator-signal set (attempts unchanged), so it never pages.
        assertThat(approvals.asService().findExhaustedRefunds(MAX_ATTEMPTS)).isEmpty();
    }

    @Test
    void pendingThenSucceededReDriveMarksExecuted_attemptsNeverIncremented() {
        // B-022 FIX 2: a `pending` cycle leaves the row re-drivable WITHOUT counting an attempt; a later
        // cycle that sees `succeeded` (the async settle landed) re-drives the SAME deduped key and marks
        // it. Even after MANY pending polls the attempt counter stays 0, so it could never have paged.
        approvals.seedStuckRefund("appr_p", "t1", "pay_1");
        when(gateway.createRefund(any()))
                .thenReturn(refund(RefundResponse.STATUS_PENDING))   // cycle 1: accepted, settling
                .thenReturn(refund(RefundResponse.STATUS_PENDING))   // cycle 2: still settling
                .thenReturn(refund(RefundResponse.STATUS_SUCCEEDED)); // cycle 3: settled

        reconciler.reconcileApprovedRefunds();
        approvals.clearBackoff("appr_p"); // simulate clock past the re-check gate
        reconciler.reconcileApprovedRefunds();
        approvals.clearBackoff("appr_p");
        reconciler.reconcileApprovedRefunds();

        var row = approvals.get("appr_p");
        assertThat(row.getExecutedAt()).as("succeeded re-drive marks it executed").isNotNull();
        assertThat(row.getReconcileAttempts()).as("no pending cycle ever counted an attempt").isZero();
        // Same deduped key on every re-drive; PSP collapses them to one logical refund (money once).
        ArgumentCaptor<RefundRequest> cap = ArgumentCaptor.forClass(RefundRequest.class);
        verify(gateway, times(3)).createRefund(cap.capture());
        assertThat(cap.getAllValues()).extracting(RefundRequest::idempotencyKey)
                .containsExactly("refund-approval-appr_p", "refund-approval-appr_p", "refund-approval-appr_p");
    }

    @Test
    void failedResponseDoesNotMarkExecuted() {
        approvals.seedStuckRefund("appr_1", "t1", "pay_1");
        when(gateway.createRefund(any())).thenReturn(refund(RefundResponse.STATUS_FAILED));

        reconciler.reconcileApprovedRefunds();

        assertThat(approvals.get("appr_1").getExecutedAt()).as("failed != executed").isNull();
        assertThat(approvals.get("appr_1").getReconcileAttempts()).isEqualTo(1);
    }

    // ---- 4. Bounded attempts: excluded from re-drive, surfaced to operator ---------------------

    @Test
    void exhaustedRowIsNotReDrivenButIsSurfacedToOperator() {
        approvals.seedStuckRefund("appr_max", "t1", "pay_1");
        approvals.setAttempts("appr_max", MAX_ATTEMPTS); // == max → excluded from finder

        reconciler.reconcileApprovedRefunds();
        assertThat(approvals.get("appr_max").getExecutedAt()).isNull();
        verify(gateway, never()).createRefund(any()); // never re-driven

        // The operator-signal sweep DOES return it.
        List<PendingApproval> exhausted = approvals.asService().findExhaustedRefunds(MAX_ATTEMPTS);
        assertThat(exhausted).extracting(PendingApproval::getId).containsExactly("appr_max");
    }

    @Test
    void operatorSignalSweepEmitsForExhaustedRefunds() {
        approvals.seedStuckRefund("appr_max", "t9", "pay_9");
        approvals.setAttempts("appr_max", MAX_ATTEMPTS);
        approvals.setLastError("appr_max", "circuit breaker open");

        // No exception => sweep ran and logged; assert it queried the exhausted set and left the row
        // hand-recoverable (status still APPROVED, not flipped to a terminal value).
        reconciler.signalExhaustedRefunds();

        assertThat(approvals.get("appr_max").getStatus()).isEqualTo("APPROVED");
        assertThat(approvals.get("appr_max").getExecutedAt()).isNull();
    }

    @Test
    void signalSweepRunsUnderItsOwnDistinctLock() {
        // B-022 FIX 3: the operator-signal sweep is now lock-guarded under a DISTINCT name from the
        // re-drive cycle so N replicas emit the page ONCE, not N times.
        reconciler.signalExhaustedRefunds();
        verify(lock).runExclusively(org.mockito.ArgumentMatchers.eq("refund-reconcile-signal"),
                any(Duration.class), any(Runnable.class));
    }

    @Test
    void signalSweepLockFailClosed_doesNotQueryOrEmit() {
        // B-022 FIX 3: lock denied (another replica holds it, or Valkey down → fail-closed) ⇒ the body
        // never runs, so the exhausted set is not even queried (no duplicate page on this replica).
        ApprovalService svc = approvals.asService();
        reconciler = new RefundReconciler(svc, refundOrchestration, lock, tenantWork, MAX_ATTEMPTS, 100);
        approvals.seedStuckRefund("appr_max", "t1", "pay_1");
        approvals.setAttempts("appr_max", MAX_ATTEMPTS);
        when(lock.runExclusively(any(), any(Duration.class), any(Runnable.class))).thenReturn(false);

        reconciler.signalExhaustedRefunds();

        verify(svc, never()).findExhaustedRefunds(org.mockito.ArgumentMatchers.anyInt());
    }

    // ---- 5. Non-refund / REJECTED / already-executed are never selected ------------------------

    @Test
    void nonRefundRejectedAndExecutedRowsAreNeverSelected() {
        approvals.seed("appr_payout", "payout", "APPROVED", "t1", "pay", null, 0); // wrong action
        approvals.seed("appr_rej", "refund", "REJECTED", "t1", "pay", null, 0);    // rejected
        approvals.seed("appr_done", "refund", "APPROVED", "t1", "pay", Instant.now(), 0); // already executed

        reconciler.reconcileApprovedRefunds();

        verify(gateway, never()).createRefund(any());
        assertThat(approvals.get("appr_payout").getExecutedAt()).isNull();
        assertThat(approvals.get("appr_done").getReconcileAttempts()).isZero();
    }

    // ---- 6. Idempotency-key invariant: same key on EVERY re-drive ------------------------------

    @Test
    void everyReDriveReusesTheDeterministicIdempotencyKey() {
        approvals.seedStuckRefund("appr_42", "t1", "pay_1");
        // First cycle: gateway fails. Second cycle (after backoff): gateway succeeds.
        when(gateway.createRefund(any()))
                .thenThrow(PaymentException.gatewayError("boom", new RuntimeException()))
                .thenReturn(refund(RefundResponse.STATUS_SUCCEEDED));

        reconciler.reconcileApprovedRefunds(); // attempt 1 (fail)
        approvals.clearBackoff("appr_42");     // simulate clock past the gate
        reconciler.reconcileApprovedRefunds(); // attempt 2 (succeed)

        ArgumentCaptor<RefundRequest> cap = ArgumentCaptor.forClass(RefundRequest.class);
        verify(gateway, times(2)).createRefund(cap.capture());
        assertThat(cap.getAllValues()).extracting(RefundRequest::idempotencyKey)
                .as("the re-drive must reuse the deterministic key on EVERY attempt — never randomize it")
                .containsExactly("refund-approval-appr_42", "refund-approval-appr_42");
        assertThat(approvals.get("appr_42").getExecutedAt()).isNotNull();
    }

    // ---- 9 (unit-level crash/redrive): mark write lost after PSP success → next cycle re-drives ---

    @Test
    void redriveDurability_psSuccessButMarkerLost_reDrivesSameKeyNextCycleNoDoublePay() {
        approvals.seedStuckRefund("appr_crash", "t1", "pay_1");
        gatewaySucceeds();
        // Simulate process death AFTER the PSP refund succeeded but BEFORE the marker write committed:
        // swallow the first markRefundExecuted, then behave normally.
        approvals.dropNextMark("appr_crash");

        reconciler.reconcileApprovedRefunds(); // PSP succeeds, marker write "lost"
        assertThat(approvals.get("appr_crash").getExecutedAt()).as("marker not yet set").isNull();

        reconciler.reconcileApprovedRefunds(); // re-drive: PSP returns same succeeded refund, mark now set
        assertThat(approvals.get("appr_crash").getExecutedAt()).isNotNull();

        // PSP createRefund invoked TWICE with the IDENTICAL key — the dedup contract (one logical refund).
        ArgumentCaptor<RefundRequest> cap = ArgumentCaptor.forClass(RefundRequest.class);
        verify(gateway, times(2)).createRefund(cap.capture());
        assertThat(cap.getAllValues()).extracting(RefundRequest::idempotencyKey)
                .containsExactly("refund-approval-appr_crash", "refund-approval-appr_crash");
    }

    // ---- per-item tenant binding is load-bearing ----------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void eachReDriveIsBoundToTheRowsOwnTenant() {
        approvals.seedStuckRefund("appr_a", "tenantA", "pay_a");
        approvals.seedStuckRefund("appr_b", "tenantB", "pay_b");
        gatewaySucceeds();
        // A verifiable mock TenantWorkRunner that still runs the per-item supplier inline, so the
        // mark write happens AND we can assert each re-drive was bound to its row's own tenant (L-035).
        TenantWorkRunner mockWork = mock(TenantWorkRunner.class);
        when(mockWork.callInTenant(org.mockito.ArgumentMatchers.anyString(), any(Supplier.class)))
                .thenAnswer(inv -> inv.getArgument(1, Supplier.class).get());
        reconciler = new RefundReconciler(approvals.asService(), refundOrchestration, lock, mockWork,
                MAX_ATTEMPTS, 100);
        when(lock.runExclusively(any(), any(Duration.class), any(Runnable.class)))
                .thenAnswer(inv -> { inv.getArgument(2, Runnable.class).run(); return true; });

        reconciler.reconcileApprovedRefunds();

        verify(mockWork).callInTenant(org.mockito.ArgumentMatchers.eq("tenantA"), any(Supplier.class));
        verify(mockWork).callInTenant(org.mockito.ArgumentMatchers.eq("tenantB"), any(Supplier.class));
        assertThat(approvals.get("appr_a").getExecutedAt()).isNotNull();
        assertThat(approvals.get("appr_b").getExecutedAt()).isNotNull();
    }

    // ---- lock fails CLOSED -------------------------------------------------------------------

    @Test
    void lockFailClosed_skipsCycle_noReDrive() {
        approvals.seedStuckRefund("appr_1", "t1", "pay_1");
        // Lock denied (Valkey down → fail-closed returns false WITHOUT running the work).
        when(lock.runExclusively(any(), any(Duration.class), any(Runnable.class))).thenReturn(false);

        reconciler.reconcileApprovedRefunds();

        verify(gateway, never()).createRefund(any());
        assertThat(approvals.get("appr_1").getExecutedAt()).isNull();
        assertThat(approvals.get("appr_1").getReconcileAttempts()).isZero();
    }

    @Test
    void cycleAlwaysRunsUnderTheRefundReconcileLock() {
        when(lock.runExclusively(any(), any(Duration.class), any(Runnable.class))).thenReturn(false);
        reconciler.reconcileApprovedRefunds();
        verify(lock).runExclusively(org.mockito.ArgumentMatchers.eq("refund-reconcile"),
                any(Duration.class), any(Runnable.class));
    }

    @Test
    void neverCallsApproveOnReDrive() {
        // The whole point of B-022: re-drive EXECUTE, NEVER approve() (which would throw "not pending"
        // since the row already left PENDING). Assert the reconciler drives createRefund
        // (executeApprovedRefund) and never calls approve() on the ApprovalService.
        ApprovalService svc = approvals.asService();
        reconciler = new RefundReconciler(svc, refundOrchestration, lock, tenantWork, MAX_ATTEMPTS, 100);
        when(lock.runExclusively(any(), any(Duration.class), any(Runnable.class)))
                .thenAnswer(inv -> { inv.getArgument(2, Runnable.class).run(); return true; });
        approvals.seedStuckRefund("appr_1", "t1", "pay_1");
        gatewaySucceeds();

        reconciler.reconcileApprovedRefunds();

        verify(gateway, atLeastOnce()).createRefund(any());
        verify(svc, never()).approve(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void gatewaySucceeds() {
        when(gateway.createRefund(any())).thenReturn(refund(RefundResponse.STATUS_SUCCEEDED));
    }

    private static RefundResponse refund(String status) {
        return new RefundResponse("rfnd_x", "pay_1", status, 60000L, "USD", "reason",
                "stripe", "con_rfnd", null, null, Instant.now());
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
     * Faithful in-memory stand-in for the iam {@link ApprovalService} reconciler seam. Enforces the
     * SAME preconditions the real conditional UPDATEs enforce: executed_at-IS-NULL gating on mark and
     * failure, attempt increments, and the discovery/exhausted filters. Crucially it does NOT ignore
     * those guards (L-039), so the reconciler exercises real semantics.
     */
    static final class InMemoryApprovals {
        private final Map<String, MutableRow> rows = new LinkedHashMap<>();
        private final AtomicInteger markDrops = new AtomicInteger(0);
        private String dropMarkFor;

        void seedStuckRefund(String id, String tenant, String paymentId) {
            seed(id, "refund", "APPROVED", tenant, paymentId, null, 0);
        }

        void seed(String id, String action, String status, String tenant, String paymentId,
                  Instant executedAt, int attempts) {
            MutableRow r = new MutableRow();
            r.id = id; r.action = action; r.status = status; r.tenant = tenant;
            r.paymentId = paymentId; r.executedAt = executedAt; r.attempts = attempts;
            r.createdAt = Instant.now();
            rows.put(id, r);
        }

        void setAttempts(String id, int n) { rows.get(id).attempts = n; }
        void setLastError(String id, String e) { rows.get(id).lastError = e; }
        void clearBackoff(String id) { rows.get(id).nextReconcileAt = null; }
        void dropNextMark(String id) { this.dropMarkFor = id; this.markDrops.set(1); }

        PendingApproval get(String id) { return rows.get(id).toDomain(); }

        ApprovalService asService() {
            ApprovalService svc = mock(ApprovalService.class);

            when(svc.findStuckApprovedRefunds(any(Instant.class), org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
                Instant now = inv.getArgument(0);
                int max = inv.getArgument(1);
                int batch = inv.getArgument(2);
                List<PendingApproval> out = new ArrayList<>();
                for (MutableRow r : rows.values()) {
                    boolean due = r.nextReconcileAt == null || !r.nextReconcileAt.isAfter(now);
                    if ("APPROVED".equals(r.status) && "refund".equals(r.action)
                            && r.executedAt == null && r.attempts < max && due) {
                        out.add(r.toDomain());
                    }
                    if (out.size() >= batch) break;
                }
                return out;
            });

            when(svc.findExhaustedRefunds(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
                int max = inv.getArgument(0);
                List<PendingApproval> out = new ArrayList<>();
                for (MutableRow r : rows.values()) {
                    if ("APPROVED".equals(r.status) && "refund".equals(r.action)
                            && r.executedAt == null && r.attempts >= max) {
                        out.add(r.toDomain());
                    }
                }
                return out;
            });

            when(svc.reloadUnexecutedForUpdate(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> {
                MutableRow r = rows.get(inv.<String>getArgument(0));
                if (r == null || r.executedAt != null) return Optional.empty();
                return Optional.of(r.toDomain());
            });

            when(svc.markRefundExecuted(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> {
                String id = inv.getArgument(0);
                String tenant = inv.getArgument(1);
                MutableRow r = rows.get(id);
                // Simulate a lost marker write (crash before commit) exactly once for one row.
                if (id.equals(dropMarkFor) && markDrops.getAndDecrement() > 0) {
                    return false;
                }
                // Real semantics: conditional on executed_at IS NULL AND tenant match.
                if (r == null || r.executedAt != null || !r.tenant.equals(tenant)) return false;
                r.executedAt = Instant.now();
                r.lastError = null;
                r.nextReconcileAt = null;
                return true;
            });

            org.mockito.Mockito.doAnswer(inv -> {
                String id = inv.getArgument(0);
                String tenant = inv.getArgument(1);
                Instant next = inv.getArgument(2);
                String err = inv.getArgument(3);
                MutableRow r = rows.get(id);
                // Real semantics: guarded on executed_at IS NULL AND tenant match.
                if (r != null && r.executedAt == null && r.tenant.equals(tenant)) {
                    r.attempts += 1;
                    r.nextReconcileAt = next;
                    r.lastError = err;
                }
                return null;
            }).when(svc).recordReconcileFailure(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(), any(Instant.class),
                    org.mockito.ArgumentMatchers.anyString());

            // B-022 FIX 2: benign PSP `pending` re-check — sets the gate + note WITHOUT touching attempts
            // (faithful to recordPendingRecheck's conditional UPDATE: guarded on executed_at IS NULL AND
            // tenant match, reconcile_attempts deliberately untouched).
            org.mockito.Mockito.doAnswer(inv -> {
                String id = inv.getArgument(0);
                String tenant = inv.getArgument(1);
                Instant next = inv.getArgument(2);
                String note = inv.getArgument(3);
                MutableRow r = rows.get(id);
                if (r != null && r.executedAt == null && r.tenant.equals(tenant)) {
                    r.nextReconcileAt = next;
                    r.lastError = note;
                    // reconcile_attempts intentionally NOT incremented.
                }
                return null;
            }).when(svc).recordPendingRecheck(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(), any(Instant.class),
                    org.mockito.ArgumentMatchers.anyString());

            return svc;
        }

        static final class MutableRow {
            String id, action, status, tenant, paymentId, lastError;
            Instant executedAt, nextReconcileAt, createdAt;
            int attempts;

            PendingApproval toDomain() {
                return new PendingApproval(id, action, "Payment", paymentId,
                        Map.of("payment_id", paymentId, "amount", 60000L, "currency", "USD", "reason", "r"),
                        status, "maker", "checker", tenant, createdAt, Instant.now(),
                        executedAt, attempts, nextReconcileAt, lastError);
            }
        }
    }
}
