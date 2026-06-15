package io.nexuspay.marketplace.adapter.in.scheduler;

import io.nexuspay.common.rls.SystemTransactional;
import io.nexuspay.marketplace.application.service.PayoutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Scheduled job that processes pending payouts on a configurable interval.
 * Disabled by default; enable via {@code nexuspay.marketplace.payout-scheduler.enabled=true}.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "nexuspay.marketplace.payout-scheduler", name = "enabled", havingValue = "true")
public class PayoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(PayoutScheduler.class);

    // SEC-11: name + TTL for the cross-instance fail-closed payout lock. TTL comfortably exceeds a
    // single disbursement batch; it is renewed at ttl/3 while the cycle runs (mirrors
    // RefundReconciler L58-61 / L92-96).
    private static final String LOCK_NAME = "payout-processing";
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    private final PayoutService payoutService;
    private final MarketplaceSchedulerLock schedulerLock;

    public PayoutScheduler(PayoutService payoutService, MarketplaceSchedulerLock schedulerLock) {
        this.payoutService = payoutService;
        this.schedulerLock = schedulerLock;
    }

    @SystemTransactional
    @Scheduled(fixedDelayString = "${nexuspay.marketplace.payout-scheduler.interval-ms:60000}")
    public void processPayouts() {
        log.debug("Payout scheduler tick");
        // SEC-11: wrap the cycle in the fail-CLOSED distributed lock so multi-replica schedulers do
        // not both run the batch. Keep @SystemTransactional so cross-tenant discovery + the atomic
        // per-payout claim still run under nexuspay_system BYPASSRLS. The lock REDUCES contention; the
        // atomic claim inside processPendingPayouts is the real exactly-once-disbursement guarantee.
        schedulerLock.runExclusively(LOCK_NAME, LOCK_TTL, payoutService::processPendingPayouts);
    }
}
