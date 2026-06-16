package io.nexuspay.marketplace.application.sim;

import io.nexuspay.marketplace.application.port.out.MarketplaceEventPublisher;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.application.port.out.PayoutExecutionPort;
import io.nexuspay.marketplace.application.port.out.PayoutExecutionPort.PayoutExecutionRequest;
import io.nexuspay.marketplace.application.port.out.PayoutExecutionPort.PayoutExecutionResult;
import io.nexuspay.marketplace.application.service.PayoutReconcileService;
import io.nexuspay.marketplace.domain.Payout;
import io.nexuspay.marketplace.domain.PayoutMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
 * SEC-25 money-invariant SOAK test — proves the deterministic-key dedup contract that makes the
 * PayoutReconciler safe holds under maximal contention.
 *
 * <p>Fires the SAME stuck payout's re-drive ({@link PayoutReconcileService#redrive}) from N=16
 * threads x 500 calls CONCURRENTLY (the SERIALIZABLE-soak shape mirroring
 * {@code RefundDeterministicKeyIdempotencySoakTest}). Every concurrent re-drive forwards the IDENTICAL
 * idempotency key {@code "payout-<id>"} to the {@link PayoutExecutionPort}; a fake-PSP stand-in (a
 * thread-safe set keyed on that key) then dedups to EXACTLY ONE payout effect — modelling the PSP's
 * server-side Idempotency-Key dedup (B-009). This is the contract the eventual GAP-062 adapter must
 * honour by forwarding the key as the PSP {@code Idempotency-Key} header.</p>
 *
 * <p>Tagged {@code simulation}: co-excluded from the default gate (build.gradle.kts) like the other
 * heavier on-demand stress suites, but it still COMPILES under {@code ./gradlew build}.</p>
 */
@Tag("simulation")
@DisplayName("SEC-25 payout deterministic-key idempotency — concurrent storm soak")
class PayoutDeterministicKeyIdempotencySoakTest {

    private static final int THREADS = 16;
    private static final int CALLS = 500;

    private static Payout stuckPayout(String id) {
        Payout p = new Payout();
        p.setId(id);
        p.setConnectedAccountId("ca_1");
        p.setTenantId("t1");
        p.setAmount(5000);
        p.setCurrency("USD");
        p.setMethod(PayoutMethod.BANK_TRANSFER);
        return p;
    }

    @Test
    @DisplayName("Same payout re-driven N times concurrently dedups to exactly ONE disbursement")
    void samePayoutConcurrent_dedupsToSingleDisbursement() throws Exception {
        Set<String> seenKeys = ConcurrentHashMap.newKeySet();
        Set<String> distinctKeysForwarded = ConcurrentHashMap.newKeySet();
        AtomicInteger appliedDisbursements = new AtomicInteger();

        PayoutExecutionPort fakePsp = mock(PayoutExecutionPort.class);
        when(fakePsp.execute(any(PayoutExecutionRequest.class))).thenAnswer(inv -> {
            PayoutExecutionRequest req = inv.getArgument(0);
            distinctKeysForwarded.add(req.idempotencyKey());
            if (seenKeys.add(req.idempotencyKey())) {
                appliedDisbursements.incrementAndGet(); // first time for this key -> applied once
            }
            return new PayoutExecutionResult(true, "pex_" + req.idempotencyKey(), null);
        });

        PayoutReconcileService svc = new PayoutReconcileService(
                mock(MarketplaceRepository.class), fakePsp, mock(MarketplaceEventPublisher.class));
        Payout payout = stuckPayout("po_42");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CALLS);
        try {
            for (int i = 0; i < CALLS; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        svc.redrive(payout);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown(); // release all threads at once -> maximal contention
            assertThat(done.await(60, TimeUnit.SECONDS))
                    .as("all concurrent payout re-drives completed").isTrue();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(distinctKeysForwarded)
                .as("every concurrent re-drive uses the identical deterministic idempotency key")
                .containsExactly("payout-po_42");
        assertThat(appliedDisbursements.get())
                .as("deterministic-key dedup -> exactly one disbursement (no double-pay under contention)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Distinct payouts each get their own deterministic key (no cross-collision)")
    void distinctPayouts_getDistinctKeys() throws Exception {
        Set<String> keys = ConcurrentHashMap.newKeySet();
        PayoutExecutionPort fakePsp = mock(PayoutExecutionPort.class);
        when(fakePsp.execute(any(PayoutExecutionRequest.class))).thenAnswer(inv -> {
            PayoutExecutionRequest req = inv.getArgument(0);
            keys.add(req.idempotencyKey());
            return new PayoutExecutionResult(true, "pex_" + req.idempotencyKey(), null);
        });
        PayoutReconcileService svc = new PayoutReconcileService(
                mock(MarketplaceRepository.class), fakePsp, mock(MarketplaceEventPublisher.class));

        int distinct = 200;
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int i = 0; i < distinct; i++) {
                final int idx = i;
                tasks.add(() -> {
                    svc.redrive(stuckPayout("po_" + idx));
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
        assertThat(keys).hasSize(distinct);
    }
}
