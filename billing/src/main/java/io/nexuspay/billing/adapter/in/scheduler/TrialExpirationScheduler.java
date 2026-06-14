package io.nexuspay.billing.adapter.in.scheduler;

import io.nexuspay.billing.application.service.TrialManagementService;
import io.nexuspay.common.rls.SystemTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Daily scheduler that converts expired trial subscriptions to active.
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
@Component
public class TrialExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrialExpirationScheduler.class);

    private final TrialManagementService trialService;
    private final SchedulerLock schedulerLock;

    public TrialExpirationScheduler(TrialManagementService trialService, SchedulerLock schedulerLock) {
        this.trialService = trialService;
        this.schedulerLock = schedulerLock;
    }

    /**
     * Runs daily at 1:00 AM — converts expired trials.
     * Guarded by a cross-instance lock so only one replica runs per cycle (B-001).
     *
     * <p>B-002: cross-tenant discovery sweep — {@code findExpiredTrials} scans ALL tenants, so it
     * runs on the BYPASSRLS role like its twin {@code RenewalScheduler}. The lock callback executes
     * synchronously on this thread, so the SYSTEM pin propagates into the inner @Transactional
     * {@code TrialManagementService.convertExpiredTrials}. The per-subscription WRITES still need
     * per-tenant TenantContext binding to keep WITH CHECK armed — tracked in B-002-activation-tenant.</p>
     */
    @SystemTransactional
    @Scheduled(cron = "0 0 1 * * *")
    public void convertExpiredTrials() {
        schedulerLock.runExclusively("trial-expiration", Duration.ofHours(1), () -> {
            log.info("Starting trial expiration check");
            int converted = trialService.convertExpiredTrials();
            log.info("Trial expiration check complete: {} subscriptions converted", converted);
        });
    }
}
