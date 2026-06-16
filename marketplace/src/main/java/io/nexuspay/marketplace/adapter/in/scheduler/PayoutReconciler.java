package io.nexuspay.marketplace.adapter.in.scheduler;

import io.nexuspay.common.rls.SystemTransactional;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.marketplace.application.port.out.PayoutExecutionPort.PayoutExecutionResult;
import io.nexuspay.marketplace.application.service.PayoutReconcileService;
import io.nexuspay.marketplace.domain.Payout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SEC-25: reconciler for payouts stuck in PROCESSING after a crash mid-disbursement.
 *
 * <p><b>The defect.</b> {@code PayoutService.processPendingPayouts} atomically claims a payout
 * (UPDATE ... WHERE status='PENDING', committable — SEC-11) and only THEN disburses at the PSP and
 * writes the terminal markPaid/markFailed. A crash anywhere after the claim COMMITS and before that
 * terminal save strands the row in PROCESSING forever: the scheduler's finder selects only
 * status='PENDING', so it NEVER re-selects a PROCESSING row. Money may be UN-disbursed and never
 * retried, and no existing component recovers it. That is what this adds.</p>
 *
 * <p><b>The fix.</b> A scheduled sweep re-drives the disbursement for rows stuck PROCESSING longer
 * than a threshold. The re-drive rebuilds the SAME deterministic idempotency key
 * {@code "payout-<id>"} (B-009) the ORIGINAL disburse used, which the PSP dedups — so a re-drive
 * racing a late-completing original both send the identical key and the PSP collapses them to ONE
 * disbursement. Money moves exactly once. A row leaves PROCESSING for PAID (PSP success) or FAILED
 * (terminal PSP failure); a transient error leaves it PROCESSING for a bounded next pass.</p>
 *
 * <p><b>Posture (mirrors B-022's {@code RefundReconciler}):</b>
 * <ul>
 *   <li>the SAME cross-instance {@link MarketplaceSchedulerLock} the {@code PayoutScheduler} uses —
 *       FAIL-CLOSED on Valkey down (ADR-006 / L-018), under a DISTINCT name {@code payout-reconcile}
 *       so reconcile and the normal payout cycle do not contend;</li>
 *   <li>{@code @SystemTransactional} discovery (BYPASSRLS) so the cross-tenant finder sees all
 *       tenants; each per-item write bound to its own tenant via {@link TenantWorkRunner#callInTenant}
 *       so RLS WITH CHECK on payouts (V4020) scopes it (invariant 5 / L-054/L-055);</li>
 *   <li>bounded {@code reconcile_attempts < maxAttempts} + exponential backoff (2^n min, capped 60),
 *       leaving the row PROCESSING on a transient failure so it re-drives next cycle (never a
 *       stranding state);</li>
 *   <li>a separate lower-cadence operator-signal sweep logs ERROR for the attempts-exhausted tail,
 *       guarded by a DISTINCT lock so N replicas page ONCE.</li>
 * </ul></p>
 *
 * <p><b>No-double-pay defense in depth.</b> Beyond the deduped key: each re-drive re-loads the row
 * FOR UPDATE and re-checks it is still PROCESSING (skip if already finalized), and the terminal
 * writes are conditional UPDATEs on status='PROCESSING' so two writers can never both finalize.</p>
 */
@Component
@ConditionalOnProperty(name = "nexuspay.marketplace.payout-reconciler.enabled",
        havingValue = "true", matchIfMissing = true)
public class PayoutReconciler {

    private static final Logger log = LoggerFactory.getLogger(PayoutReconciler.class);

    private static final String LOCK_NAME = "payout-reconcile";
    private static final String SIGNAL_LOCK_NAME = "payout-reconcile-signal";
    // TTL > a single PSP payout round-trip; renewed at ttl/3 while the cycle runs.
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final long MAX_BACKOFF_MINUTES = 60;

    private final PayoutReconcileService reconcileService;
    private final MarketplaceSchedulerLock schedulerLock;
    private final TenantWorkRunner tenantWork;
    private final int maxAttempts;
    private final int batchSize;
    private final long stuckThresholdMs;

    public PayoutReconciler(PayoutReconcileService reconcileService,
                            MarketplaceSchedulerLock schedulerLock,
                            TenantWorkRunner tenantWork,
                            @Value("${nexuspay.marketplace.payout-reconciler.max-attempts:5}") int maxAttempts,
                            @Value("${nexuspay.marketplace.payout-reconciler.batch-size:100}") int batchSize,
                            @Value("${nexuspay.marketplace.payout-reconciler.stuck-threshold-ms:300000}") long stuckThresholdMs) {
        this.reconcileService = reconcileService;
        this.schedulerLock = schedulerLock;
        this.tenantWork = tenantWork;
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
        this.stuckThresholdMs = stuckThresholdMs;
    }

    /**
     * Runs every 60s by default — a stuck payout self-heals in ~1 min after crossing the threshold.
     * The whole cycle is guarded by the cross-instance fail-closed lock, so only one replica re-drives.
     */
    @SystemTransactional
    @Scheduled(fixedDelayString = "${nexuspay.marketplace.payout-reconciler.fixed-delay-ms:60000}")
    public void reconcileStuckPayouts() {
        schedulerLock.runExclusively(LOCK_NAME, LOCK_TTL, this::doReconcile);
    }

    /**
     * Discovery as SYSTEM (cross-tenant); each re-drive bound to its own tenant so one tenant's failure
     * does not abort the sweep (separate try/catch per item, like B-022).
     */
    void doReconcile() {
        Instant now = Instant.now();
        Instant cutoff = now.minusMillis(stuckThresholdMs);
        List<Payout> stuck = reconcileService.findStuckProcessing(cutoff, now, maxAttempts, batchSize);
        if (stuck.isEmpty()) {
            log.debug("No stuck PROCESSING payouts to reconcile");
            return;
        }
        log.info("Reconciling {} stuck PROCESSING payout(s)", stuck.size());

        int reconciled = 0;
        int unresolved = 0;
        for (Payout p : stuck) {
            try {
                // Per-item write bound to the row's OWN tenant: RLS WITH CHECK on payouts only admits
                // the terminal/attempts UPDATE because the bound tenant == the row's tenant.
                Boolean ok = tenantWork.callInTenant(p.getTenantId(), () -> reconcileOne(p));
                if (Boolean.TRUE.equals(ok)) {
                    reconciled++;
                } else {
                    unresolved++;
                }
            } catch (Exception e) {
                // One tenant's failure must not abort the sweep.
                unresolved++;
                log.error("Payout reconcile failed payout={} tenant={}: {}",
                        p.getId(), p.getTenantId(), e.getMessage(), e);
            }
        }
        log.info("Payout reconcile cycle complete: processed={}, reconciled={}, unresolved={}",
                stuck.size(), reconciled, unresolved);
    }

    /**
     * Re-drives one stuck payout inside the tenant-bound tx.
     *
     * <p>Sequence: re-load FOR UPDATE and re-check still PROCESSING (skip if the original or a
     * concurrent pass already finalized) → re-drive with the SAME deduped key → branch:
     * <ul>
     *   <li><b>success</b> → PROCESSING -> PAID (terminal, conditional UPDATE);</li>
     *   <li><b>terminal PSP failure</b> ({@code success=false}) → PROCESSING -> FAILED (terminal);</li>
     *   <li><b>thrown gateway exception</b> (transient/CB-open) → bump attempts + exponential backoff,
     *       leave the row PROCESSING so it re-drives next cycle (bounded).</li>
     * </ul></p>
     *
     * @return {@code true} iff this call drove the payout to PAID; {@code false} otherwise (skipped,
     *         terminal-failed, or transient — all handled, none stranded).
     */
    boolean reconcileOne(Payout discovered) {
        // Secondary intra-cycle guard: re-load FOR UPDATE; bail if no longer PROCESSING.
        Optional<Payout> current = reconcileService.reloadStuckForUpdate(discovered.getId());
        if (current.isEmpty()) {
            log.debug("Payout {} already finalized or gone; skipping re-drive", discovered.getId());
            return false;
        }
        Payout p = current.get();

        PayoutExecutionResult result;
        try {
            // Re-drive with Payout.idempotencyKey("payout-<id>") — the SAME key the original disburse
            // used, so the PSP dedups (B-009). NO double-pay even if the original actually settled.
            // NOTE (SEC-25): this PSP round-trip runs while the reloadStuckForUpdate FOR UPDATE row lock
            // is still held (it releases at the callInTenant tx commit). The GAP-062 adapter MUST enforce
            // a hard client timeout well under LOCK_TTL (5 min) so a hung PSP cannot pin the lock/connection
            // past the lease — see PayoutExecutionPort#execute. A throw here is recorded as transient and
            // re-driven next cycle (deduped key → still no double-pay).
            result = reconcileService.redrive(p);
        } catch (RuntimeException e) {
            // Gateway threw (transient error / circuit-breaker open) — payout NOT confirmed. Record a
            // bounded failure (bump attempts + backoff) and leave the row PROCESSING (re-drivable).
            recordFailure(p, e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }

        if (result != null && result.success()) {
            // Conditional on PROCESSING — two writers can't both finalize.
            reconcileService.markPaid(p.getId(), p.getTenantId(), result.externalReference());
            return true;
        }

        // Terminal PSP failure -> FAILED. (This port has no async-`pending` state; if GAP-062 adds one,
        // mirror B-022's benign-pending branch here: re-check gate, attempts untouched.)
        String reason = result != null ? result.failureReason() : "null payout-execution result";
        reconcileService.markFailed(p.getId(), p.getTenantId(), "gateway payout not successful: " + reason);
        return false;
    }

    /**
     * Records a transient re-drive failure: bumps {@code reconcile_attempts}, sets the backoff gate
     * ({@code next_reconcile_at = now + min(2^attempts, 60) min}) and last error, leaving the row
     * PROCESSING. The backoff exponent uses the attempt count we are ABOUT to record (current + 1).
     */
    private void recordFailure(Payout p, String error) {
        long attemptsAfter = (long) p.getReconcileAttempts() + 1;
        long backoffMinutes = Math.min((long) Math.pow(2, attemptsAfter), MAX_BACKOFF_MINUTES);
        Instant nextReconcileAt = Instant.now().plus(Duration.ofMinutes(backoffMinutes));
        reconcileService.recordFailure(p.getId(), p.getTenantId(), nextReconcileAt, error);
        log.warn("Payout re-drive failed payout={} tenant={} attempts={} nextRetry={}: {}",
                p.getId(), p.getTenantId(), attemptsAfter, nextReconcileAt, error);
    }

    /**
     * Operator-signal sweep — every 15 min by default. Surfaces the attempts-EXHAUSTED tail (PROCESSING
     * payouts out of attempts) at ERROR so a human can intervene; these are excluded from the re-drive
     * finder (so the PSP is not hammered) but must never be silently stranded. Guarded by a DISTINCT
     * lock so N replicas page ONCE. Read-only: status is never flipped, so each row stays
     * hand-recoverable (clearing reconcile_attempts re-drives the SAME idempotency key safely).
     */
    @SystemTransactional
    @Scheduled(fixedDelayString = "${nexuspay.marketplace.payout-reconciler.signal-delay-ms:900000}")
    public void signalExhaustedPayouts() {
        schedulerLock.runExclusively(SIGNAL_LOCK_NAME, LOCK_TTL, this::doSignalExhausted);
    }

    /** Read-only operator-signal body; runs only on the replica that wins {@link #SIGNAL_LOCK_NAME}. */
    void doSignalExhausted() {
        List<Payout> exhausted = reconcileService.findExhaustedProcessing(maxAttempts);
        if (exhausted.isEmpty()) {
            return;
        }
        log.error("OPERATOR SIGNAL: {} payout(s) exhausted {} reconcile attempts and remain stuck "
                + "PROCESSING — manual intervention required", exhausted.size(), maxAttempts);
        for (Payout p : exhausted) {
            log.error("  stuck payout id={} tenant={} account={} amount={}{} lastError=\"{}\"",
                    p.getId(), p.getTenantId(), p.getConnectedAccountId(),
                    p.getAmount(), p.getCurrency(), p.getLastReconcileError());
        }
    }
}
