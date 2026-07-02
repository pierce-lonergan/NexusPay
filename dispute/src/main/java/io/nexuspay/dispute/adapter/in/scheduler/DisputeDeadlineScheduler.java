package io.nexuspay.dispute.adapter.in.scheduler;

import io.nexuspay.common.rls.SystemTransactional;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.dispute.application.port.out.DisputeRepository;
import io.nexuspay.dispute.application.service.DisputeLifecycleService;
import io.nexuspay.dispute.config.DisputeSchedulerProperties;
import io.nexuspay.dispute.domain.Dispute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * GAP-033 (reliability): evidence-deadline expiry sweep. A dispute past its {@code evidence_due_date}
 * with no response would otherwise stay {@code OPENED}/{@code EVIDENCE_NEEDED} FOREVER (today
 * {@code expire()} is only driven by the inbound {@code dispute.expired} webhook, which may never
 * arrive). This scheduled sweep finds genuinely-overdue pre-terminal disputes and calls the EXISTING
 * {@link DisputeLifecycleService#expire(String)} once per dispute.
 *
 * <h3>MONEY-SAFETY — {@code expire()} TOUCHES THE LEDGER (verified), and the safety is intrinsic.</h3>
 * {@code expire()} is NOT a pure status flip: when the dispute actually transitions it calls
 * {@code ledgerPort.finaliseChargebackExpense(...)} (DisputeLifecycleService.expire lines 299-303) — the
 * same money-moving posting as {@code lose()} (DR chargeback_expense, CR chargeback_reserve). WAVE-1's
 * CARDINAL RULE therefore applies, and it is ALREADY SATISFIED by {@code expire()}, which this scheduler
 * must NOT weaken:
 * <ul>
 *   <li><b>Atomicity is intrinsic</b> — {@code expire()} is a single {@code @Transactional} in which the
 *       status transition, event persist, ledger finalisation, and outbox publish all commit together; a
 *       ledger failure rolls the whole expire back. This scheduler calls {@code expire()} AS-IS and never
 *       wraps the ledger call in a nested tx or a catch-and-continue INSIDE one dispute's expire.</li>
 *   <li><b>Idempotency is intrinsic + double-guarded</b> — {@code dispute.expire()} no-ops on an
 *       already-terminal dispute (produces no event), and the service posts to the ledger ONLY when the
 *       transition actually happened. So a re-run, a racing replica, or a dispute already EXPIRED by the
 *       inbound webhook between discovery and drive does NOT double-post the expense.</li>
 * </ul>
 * The scheduler's only jobs are: (a) find the right disputes, (b) call {@code expire()} once per dispute
 * UNDER THE DISPUTE'S OWN TENANT, (c) isolate one dispute's failure from the rest, and (d) never
 * bulk-expire many disputes in one transaction (that would couple N ledger postings — one failure would
 * roll back all). Per-item {@code callInTenant} gives each expire its own {@code REQUIRES_NEW} tx =
 * exactly one-tx-per-dispute isolation.
 *
 * <h3>Posture (mirrors the vault {@code KeyRotationJobService}, NOT {@code RefundReconciler}).</h3>
 * <ul>
 *   <li><b>NO cross-instance lock</b> — the dispute module allows only {@code {common, ledger}} deps and
 *       has no Redis / no {@code GatewaySchedulerLock}, and does not need one: {@code expire()} is
 *       idempotent, so two replicas racing the same dispute both yield ONE ledger posting (the loser hits
 *       the terminal-state no-op). A lock would be defense-in-depth, not correctness, and is deliberately
 *       out of scope (same call {@code KeyRotationJobService} makes).</li>
 *   <li><b>{@code @SystemTransactional} discovery</b> so the cross-tenant {@code findExpirable} sees every
 *       tenant's overdue disputes (dispute RLS is dormant today but this stays correct on activation);
 *       each per-dispute expire is bound to that dispute's OWN tenant via
 *       {@link TenantWorkRunner#callInTenant} so RLS WITH CHECK scopes the write.</li>
 *   <li><b>Per-dispute failure isolation</b> — each expire is wrapped in try/catch AROUND the whole
 *       {@code expire()} call (never inside it); one bad dispute never aborts the sweep.</li>
 * </ul>
 *
 * <h3>Flag / cadence.</h3>
 * Enabled by default, disablable via {@code nexuspay.dispute.deadline-expiry.enabled=false}
 * ({@code matchIfMissing=true}). Fixed-delay 5 min default, batch-size 200 default (see
 * {@link DisputeSchedulerProperties}).
 */
@Component
@ConditionalOnProperty(name = "nexuspay.dispute.deadline-expiry.enabled",
        havingValue = "true", matchIfMissing = true)
public class DisputeDeadlineScheduler {

    private static final Logger log = LoggerFactory.getLogger(DisputeDeadlineScheduler.class);

    private final DisputeRepository disputeRepository;
    private final DisputeLifecycleService lifecycleService;
    private final TenantWorkRunner tenantWork;
    private final DisputeSchedulerProperties properties;

    public DisputeDeadlineScheduler(DisputeRepository disputeRepository,
                                    DisputeLifecycleService lifecycleService,
                                    TenantWorkRunner tenantWork,
                                    DisputeSchedulerProperties properties) {
        this.disputeRepository = disputeRepository;
        this.lifecycleService = lifecycleService;
        this.tenantWork = tenantWork;
        this.properties = properties;
    }

    /**
     * Runs on a fixed delay (default 5 min). Discovery is SYSTEM-pinned so it sees all tenants; each
     * per-dispute expire is bound to its own tenant in a {@code REQUIRES_NEW} tx via {@code callInTenant}.
     */
    @SystemTransactional
    @Scheduled(fixedDelayString = "${nexuspay.dispute.deadline-expiry.fixed-delay-ms:300000}")
    public void expireOverdueDisputes() {
        int batchSize = properties.getBatchSize();
        if (batchSize <= 0) {
            batchSize = 200;
        }

        List<Dispute> due = disputeRepository.findExpirable(Instant.now(), batchSize);
        if (due.isEmpty()) {
            log.debug("No overdue disputes to expire");
            return;
        }
        log.info("Expiring {} overdue dispute(s)", due.size());

        int expired = 0;
        int failed = 0;
        for (Dispute d : due) {
            try {
                // Per-dispute write bound to the row's OWN tenant: RLS WITH CHECK on disputes only admits
                // the transition/ledger writes because the bound tenant == the dispute's tenant. The WHOLE
                // atomic expire() runs inside this one REQUIRES_NEW tx, so one dispute's rollback never
                // touches another's committed expire.
                tenantWork.runInTenant(d.getTenantId(), () -> lifecycleService.expire(d.getId()));
                expired++;
            } catch (Exception e) {
                // One dispute's failure must never abort the sweep. Its expire tx rolled back (no partial
                // ledger post), it stays in its pre-terminal state, and it is re-selected next cycle.
                failed++;
                log.error("Dispute expire failed id={} tenant={}: {}",
                        d.getId(), d.getTenantId(), e.getMessage(), e);
            }
        }

        log.info("Dispute expiry sweep complete: processed={}, expired={}, failed={}",
                due.size(), expired, failed);
    }
}
