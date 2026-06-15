package io.nexuspay.app.redteam;

import io.nexuspay.app.IntegrationTestBase;
import io.nexuspay.app.config.TestSecurityConfig;
import io.nexuspay.marketplace.application.port.in.SchedulePayoutUseCase;
import io.nexuspay.marketplace.application.port.in.SchedulePayoutUseCase.CreatePayoutCommand;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.domain.ConnectedAccount;
import io.nexuspay.marketplace.domain.KycStatus;
import io.nexuspay.marketplace.domain.PayoutMethod;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RED-TEAM (report-only, {@code @Tag("redteam")}): payout double-pay under
 * concurrency (no lock / no idempotency).
 *
 * <p><strong>Attack / fault:</strong> fire N IDENTICAL {@code createPayout} calls
 * for the same connected account concurrently (a double-click, a retry storm, or
 * two replicas racing the same disbursement). A SECURE system disburses ONCE — it
 * locks/dedups so only a single payout row is created for an identical request.</p>
 *
 * <p><strong>Why this FAILS on current main (excluded + report-only):</strong>
 * {@code PayoutService.createPayout} takes no idempotency key and holds no lock, so
 * N concurrent identical commands each create a distinct {@code Payout}, which the
 * scheduler later disburses N times → over-payment. When the payout-lock /
 * idempotency PR lands, drop {@code @Tag("redteam")} to gate it.</p>
 */
@Tag("redteam")
@Import(TestSecurityConfig.class)
@DisplayName("RED-TEAM: payout double-pay under concurrency")
class PayoutDoublePayRedteamTest extends IntegrationTestBase {

    private static final String TENANT = "default";

    @Autowired
    private SchedulePayoutUseCase payoutUseCase;

    @Autowired
    private MarketplaceRepository repository;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — payout double-pay red-team self-skips (Testcontainers required)");
    }

    @Test
    @DisplayName("concurrent identical payouts disburse exactly ONCE")
    void concurrentIdenticalPayouts_disburseOnce() throws Exception {
        // Seed an ACTIVE (KYC-verified) connected account so createPayout passes its
        // eligibility gate and reaches the (missing) idempotency/lock.
        ConnectedAccount account = ConnectedAccount.create(
                TENANT, "Red Team LLC", "redteam@example.com", "US", "USD");
        account.updateKycStatus(KycStatus.VERIFIED);
        account.activate();
        account = repository.saveAccount(account);
        final String accountId = account.getId();

        int copies = 8;
        ExecutorService pool = Executors.newFixedThreadPool(copies);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(copies);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        try {
            for (int i = 0; i < copies; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        // IDENTICAL command each time — a secure system must collapse these.
                        payoutUseCase.createPayout(new CreatePayoutCommand(
                                TENANT, accountId, 100_000L, "USD", PayoutMethod.BANK_TRANSFER, null));
                    } catch (Throwable t) {
                        // A losing writer may legitimately be rejected once a lock/idempotency
                        // guard exists; the invariant is the FINAL payout count.
                        firstError.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(repository.findPayoutsByAccountId(accountId))
                .as("N concurrent identical payouts must create exactly one payout (no double-pay)")
                .hasSize(1);
    }
}
