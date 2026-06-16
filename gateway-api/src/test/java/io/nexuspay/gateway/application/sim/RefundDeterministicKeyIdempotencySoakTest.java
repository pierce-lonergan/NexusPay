package io.nexuspay.gateway.application.sim;

import io.nexuspay.gateway.application.RefundOrchestrationService;
import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.PendingApproval;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.RefundResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Money-invariant SOAK test (IN-GATE, UNTAGGED) — part of the simulation /
 * red-team environment (see {@code docs/simulation/README.md}).
 *
 * <p>Fires the SAME approval-derived refund MANY times CONCURRENTLY and proves
 * the deterministic-key dedup contract holds under contention: every concurrent
 * execution forwards the IDENTICAL idempotency key {@code "refund-approval-"+id}
 * to the {@link PaymentGatewayPort}. A fake-PSP stand-in (a thread-safe set keyed
 * on that idempotency key) then dedups to EXACTLY ONE refund effect — modelling
 * HyperSwitch's server-side idempotency dedup (B-009).</p>
 *
 * <p><strong>Why this PASSES on current main (safe in the gate):</strong>
 * {@code RefundOrchestrationService.executeApprovedRefund} derives the key purely
 * from {@code approval.getId()} — it is referentially deterministic with no shared
 * mutable state — so N racing threads cannot produce N distinct keys. This is the
 * in-JVM, unit-level complement to the {@code RefundIdempotencyStormSimulation}
 * gatling load harness (which proves the same dedup holds under HTTP load).</p>
 *
 * <p>UNTAGGED on purpose: runs in the default {@code ./gradlew test} gate, needs
 * NO Docker (pure mocks), and raises the ratchet floors.</p>
 */
@DisplayName("Refund deterministic-key idempotency — concurrent storm soak (in-gate)")
class RefundDeterministicKeyIdempotencySoakTest {

    private static final int THREADS = 16;
    private static final int CALLS = 500;

    private PendingApproval approval(String id) {
        return new PendingApproval(id, "refund", "Payment", "pay_1",
                Map.of("payment_id", "pay_1", "amount", 60000L, "currency", "USD", "reason", "approved"),
                "APPROVED", "maker", "checker", "t1", Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("Same approval fired N times concurrently dedups to exactly ONE refund effect")
    void sameApprovalConcurrent_dedupsToSingleRefund() throws Exception {
        // Fake PSP backed by a Mockito mock: records each first-seen idempotency key
        // and counts the refund effects it actually APPLIED (a real PSP returns the
        // prior result on a duplicate key without re-moving money). The dedup oracle.
        Set<String> seenKeys = ConcurrentHashMap.newKeySet();
        AtomicInteger appliedRefunds = new AtomicInteger();
        Set<String> distinctKeysForwarded = ConcurrentHashMap.newKeySet();

        PaymentGatewayPort fakePsp = mock(PaymentGatewayPort.class);
        when(fakePsp.createRefund(any(RefundRequest.class))).thenAnswer(inv -> {
            RefundRequest req = inv.getArgument(0);
            distinctKeysForwarded.add(req.idempotencyKey());
            if (seenKeys.add(req.idempotencyKey())) {
                appliedRefunds.incrementAndGet(); // first time for this key → applied once
            }
            return refundResponse(req);
        });

        ApprovalService approvals = mock(ApprovalService.class);
        // SEC-07 (B-007): executeApprovedRefund does not call assertOwnedBy (the approved path was
        // already ownership-checked at createRefund), so a mock screening service suffices here.
        var svc = new RefundOrchestrationService(fakePsp, approvals,
                mock(io.nexuspay.payment.application.screening.ScreeningOriginService.class), 50000L);

        PendingApproval theApproval = approval("appr_42");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CALLS);
        try {
            for (int i = 0; i < CALLS; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        svc.executeApprovedRefund(theApproval);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown(); // release all threads at once → maximal contention
            assertThat(done.await(60, TimeUnit.SECONDS))
                    .as("all concurrent refund executions completed").isTrue();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        // All N racing calls forwarded the SAME deterministic key — no race produced
        // a second distinct key that would escape PSP dedup.
        assertThat(distinctKeysForwarded)
                .as("every concurrent execution uses the identical deterministic idempotency key")
                .containsExactly("refund-approval-appr_42");

        // The PSP applied the refund EXACTLY ONCE despite N concurrent submissions.
        assertThat(appliedRefunds.get())
                .as("deterministic-key dedup → exactly one refund effect (no double-refund under contention)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Distinct approvals each get their own deterministic key (no cross-collision)")
    void distinctApprovals_getDistinctKeys() throws Exception {
        Set<String> keys = ConcurrentHashMap.newKeySet();
        PaymentGatewayPort fakePsp = mock(PaymentGatewayPort.class);
        when(fakePsp.createRefund(any(RefundRequest.class))).thenAnswer(inv -> {
            RefundRequest req = inv.getArgument(0);
            keys.add(req.idempotencyKey());
            return refundResponse(req);
        });
        var svc = new RefundOrchestrationService(fakePsp, mock(ApprovalService.class),
                mock(io.nexuspay.payment.application.screening.ScreeningOriginService.class), 50000L);

        int distinct = 200;
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int i = 0; i < distinct; i++) {
                final int idx = i;
                tasks.add(() -> {
                    svc.executeApprovedRefund(approval("appr_" + idx));
                    return null;
                });
            }
            for (var f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        // 200 distinct approvals → 200 distinct deterministic keys (no collision, no key reuse).
        assertThat(keys).hasSize(distinct);
    }

    private static RefundResponse refundResponse(RefundRequest req) {
        return new RefundResponse(
                "re_" + req.idempotencyKey(), req.paymentId(), RefundResponse.STATUS_SUCCEEDED,
                req.amount(), req.currency(), req.reason(),
                "fake-psp", "conn_" + req.idempotencyKey(), null, null, Instant.now());
    }
}
