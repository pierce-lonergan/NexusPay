package io.nexuspay.marketplace.adapter.in.scheduler;

import io.nexuspay.common.rls.SystemTransactional;
import io.nexuspay.marketplace.application.service.PayoutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    private final PayoutService payoutService;

    public PayoutScheduler(PayoutService payoutService) {
        this.payoutService = payoutService;
    }

    @SystemTransactional
    @Scheduled(fixedDelayString = "${nexuspay.marketplace.payout-scheduler.interval-ms:60000}")
    public void processPayouts() {
        log.debug("Payout scheduler tick");
        payoutService.processPendingPayouts();
    }
}
