package io.nexuspay.gateway.adapter.in.scheduler;

import io.nexuspay.common.rls.SystemTransactional;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.gateway.application.RefundOrchestrationService;
import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.PendingApproval;
import io.nexuspay.payment.domain.RefundResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * B-022: reconciler for refunds stuck in APPROVED-but-unexecuted.
 *
 * <p><b>The defect.</b> {@code ApprovalService.approve()} commits the PENDING-&gt;APPROVED claim in its
 * OWN transaction; {@code RefundOrchestrationService.executeApprovedRefund(..)} then runs the gateway
 * call OUTSIDE that tx (ApprovalController.approve:48 then 52). If the gateway call throws, the row is
 * stranded APPROVED forever and never executes, and a retry of {@code approve()} throws "not pending"
 * (the row already left PENDING — ApprovalService.loadForReview:112-114). The legitimate refund then
 * needs manual recovery.</p>
 *
 * <p><b>The fix.</b> A scheduled sweep re-drives {@code executeApprovedRefund} (NEVER {@code approve()})
 * for stuck rows. {@code executeApprovedRefund} rebuilds the SAME deterministic idempotency key
 * {@code "refund-approval-<id>"} (B-009), which HyperSwitch dedups — so a re-drive racing a
 * late-completing original both send the identical key and the PSP collapses them to ONE refund.
 * Money moves exactly once. A row is marked done by stamping {@code executed_at} ONLY after the gateway
 * returns {@code isSuccessful()} (status==succeeded).</p>
 *
 * <p><b>Posture (mirrors RenewalScheduler / DeadLetterReprocessor):</b>
 * <ul>
 *   <li>cross-instance {@link GatewaySchedulerLock} — FAIL-CLOSED on Valkey down (ADR-006), so a
 *       money re-drive never runs unguarded on every replica;</li>
 *   <li>{@code @SystemTransactional} discovery (BYPASSRLS) so the cross-tenant finder sees all tenants;
 *       each per-item write bound to its own tenant via {@link TenantWorkRunner#callInTenant} so RLS
 *       WITH CHECK on pending_approvals scopes it (B-002 / L-035);</li>
 *   <li>bounded {@code reconcile_attempts < maxAttempts} + exponential backoff (2^n min, capped 60),
 *       leaving {@code executed_at} NULL on failure so the row is re-drivable next cycle (never a
 *       terminal/stranding state);</li>
 *   <li>a separate lower-cadence operator-signal sweep logs ERROR for the attempts-exhausted tail so an
 *       exhausted refund is loudly surfaced, never silently stranded — itself guarded by a DISTINCT
 *       cross-instance lock so N replicas emit the page ONCE, not N times.</li>
 * </ul></p>
 */
@Component
@ConditionalOnProperty(name = "nexuspay.refund.reconciler.enabled", havingValue = "true", matchIfMissing = true)
public class RefundReconciler {

    private static final Logger log = LoggerFactory.getLogger(RefundReconciler.class);

    private static final String LOCK_NAME = "refund-reconcile";
    private static final String SIGNAL_LOCK_NAME = "refund-reconcile-signal";
    // TTL > a single PSP refund round-trip; renewed at ttl/3 while the cycle runs.
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final long MAX_BACKOFF_MINUTES = 60;
    // Moderate, FIXED re-check cadence for a PSP `pending` (accepted, settling async) response: not a
    // failure and not exponential-backoff'd, just polled until the PSP reports succeeded (B-022 FIX 2).
    private static final Duration PENDING_RECHECK_DELAY = Duration.ofMinutes(2);

    private final ApprovalService approvalService;
    private final RefundOrchestrationService refundOrchestration;
    private final GatewaySchedulerLock schedulerLock;
    private final TenantWorkRunner tenantWork;
    private final int maxAttempts;
    private final int batchSize;

    public RefundReconciler(ApprovalService approvalService,
                            RefundOrchestrationService refundOrchestration,
                            GatewaySchedulerLock schedulerLock,
                            TenantWorkRunner tenantWork,
                            @Value("${nexuspay.refund.reconciler.max-attempts:5}") int maxAttempts,
                            @Value("${nexuspay.refund.reconciler.batch-size:100}") int batchSize) {
        this.approvalService = approvalService;
        this.refundOrchestration = refundOrchestration;
        this.schedulerLock = schedulerLock;
        this.tenantWork = tenantWork;
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
    }

    /**
     * Runs every 60s by default — a stuck refund should self-heal in ~1 min. The WHOLE cycle is guarded
     * by the cross-instance fail-closed lock, so only one replica re-drives per cycle.
     */
    @SystemTransactional
    @Scheduled(fixedDelayString = "${nexuspay.refund.reconciler.fixed-delay-ms:60000}")
    public void reconcileApprovedRefunds() {
        schedulerLock.runExclusively(LOCK_NAME, LOCK_TTL, this::doReconcile);
    }

    /**
     * Discovery as SYSTEM (cross-tenant); each re-drive bound to its own tenant in a REQUIRES_NEW tx so
     * one tenant's failure does not roll back the others (separate try/catch per item, like
     * RenewalScheduler).
     */
    void doReconcile() {
        List<PendingApproval> stuck =
                approvalService.findStuckApprovedRefunds(Instant.now(), maxAttempts, batchSize);
        if (stuck.isEmpty()) {
            log.debug("No stuck APPROVED refunds to reconcile");
            return;
        }
        log.info("Reconciling {} stuck APPROVED refund(s)", stuck.size());

        int executed = 0;
        int failed = 0;
        for (PendingApproval a : stuck) {
            try {
                // Per-item write bound to the row's OWN tenant: RLS WITH CHECK on pending_approvals
                // only admits the executed_at / attempts UPDATE because the bound tenant == row tenant.
                Boolean ok = tenantWork.callInTenant(a.getTenantId(), () -> reconcileOne(a));
                if (Boolean.TRUE.equals(ok)) {
                    executed++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                // One tenant's failure must not abort the sweep.
                failed++;
                log.error("Refund reconcile failed approval={} tenant={}: {}",
                        a.getId(), a.getTenantId(), e.getMessage(), e);
            }
        }
        log.info("Refund reconcile cycle complete: processed={}, executed={}, failed={}",
                stuck.size(), executed, failed);
    }

    /**
     * Re-drives one stuck refund INSIDE the tenant-bound REQUIRES_NEW tx on the APP role.
     *
     * <p>Sequence: re-load FOR UPDATE and re-check {@code executed_at IS NULL} (skip if another
     * replica/the original already marked it) → call {@code executeApprovedRefund} (rebuilds the same
     * deduped key) → branch on THREE outcomes (B-022 FIX 2):
     * <ul>
     *   <li><b>succeeded</b> ({@code isSuccessful()}) → stamp {@code executed_at} (conditional on NULL);</li>
     *   <li><b>pending</b> ({@code STATUS_PENDING}) → BENIGN: the PSP ACCEPTED the refund and is settling
     *       it asynchronously (the key is deduped, so money moves once). Do NOT count an attempt and do
     *       NOT treat as a failure — just set a moderate fixed re-check gate and leave {@code executed_at}
     *       NULL so a later cycle re-drives the same key and marks it once the PSP reports succeeded. A
     *       perpetually-pending refund therefore never advances toward the exhausted tail, so it can never
     *       false-page the operator-signal sweep;</li>
     *   <li><b>failed</b> ({@code STATUS_FAILED} / unexpected status / null response) OR a thrown gateway
     *       exception (PaymentException / circuit-breaker) → REAL failure: increment the attempt counter,
     *       set the exponential backoff gate + last error, leave {@code executed_at} NULL so the row
     *       re-drives next cycle (and is surfaced to the operator only once attempts are exhausted).</li>
     * </ul></p>
     *
     * <p>FOLLOW-UP (tracked, NOT built here): a max-pending-age operator signal (so a refund stuck
     * {@code pending} for an abnormally long time still gets a human look) plus a getRefund/webhook
     * settle path (so we learn of settlement without polling). Out of scope for B-022.</p>
     *
     * <p>The gateway call AND the marker write both run synchronously on THIS tenant-bound thread —
     * never an async callback — so the SYSTEM/tenant role pin (a call-scoped thread-local that does NOT
     * propagate across threads, per the DeadLetterReprocessor caveat) still covers the marker write.
     * {@code HyperSwitchPaymentAdapter.createRefund} is blocking, so this holds naturally.</p>
     *
     * @return {@code true} iff this call drove the refund to executed; {@code false} otherwise (skipped,
     *         pending/failed PSP response, or gateway failure — all re-drivable next cycle).
     */
    boolean reconcileOne(PendingApproval discovered) {
        // Secondary intra-cycle guard: re-load FOR UPDATE; bail if already executed.
        var current = approvalService.reloadUnexecutedForUpdate(discovered.getId());
        if (current.isEmpty()) {
            log.debug("Approval {} already executed or gone; skipping re-drive", discovered.getId());
            return false;
        }
        PendingApproval approval = current.get();

        RefundResponse response;
        try {
            response = refundOrchestration.executeApprovedRefund(approval);
        } catch (RuntimeException e) {
            // Gateway threw — PaymentException.gatewayError on a RestClientException
            // (HyperSwitchPaymentAdapter), OR a Resilience4j CallNotPermittedException when the
            // "hyperswitch" breaker is OPEN (createRefund has no fallback method). EITHER way the
            // refund is NOT executed: record a bounded failure (increment attempts + backoff) so it
            // is re-drivable next cycle but cannot be hammered forever. We catch RuntimeException
            // rather than only PaymentException precisely so a CB-open still counts an attempt.
            recordFailure(approval, e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }

        // (a) succeeded → mark executed (conditional on executed_at IS NULL — two writers can't both claim).
        if (response != null && response.isSuccessful()) {
            approvalService.markRefundExecuted(approval.getId(), approval.getTenantId());
            return true;
        }

        // (b) pending → PSP accepted, settling async. BENIGN, re-checkable: do NOT count an attempt and
        // do NOT treat as a failure. Set a moderate fixed re-check gate; leave executed_at NULL so a later
        // cycle re-drives the same deduped key and marks it once the PSP reports succeeded. Because attempts
        // is untouched, a perpetually-pending refund never reaches the exhausted tail (never false-pages).
        if (response != null && RefundResponse.STATUS_PENDING.equals(response.status())) {
            Instant nextRecheckAt = Instant.now().plus(PENDING_RECHECK_DELAY);
            approvalService.recordPendingRecheck(approval.getId(), approval.getTenantId(), nextRecheckAt,
                    "gateway refund pending (accepted, settling async) — re-checking, not a failure");
            log.info("Refund re-drive PENDING approval={} tenant={} nextRecheck={} (attempt counter NOT bumped)",
                    approval.getId(), approval.getTenantId(), nextRecheckAt);
            return false;
        }

        // (c) failed / unexpected status / null response → REAL failure: bump attempts + exponential backoff.
        String status = response != null ? response.status() : "null-response";
        recordFailure(approval, "gateway refund not successful: status=" + status);
        return false;
    }

    /**
     * Records a failed re-drive: bumps {@code reconcile_attempts}, sets the backoff gate
     * ({@code next_reconcile_at = now + min(2^attempts, 60) min}) and the last error. {@code executed_at}
     * stays NULL. The backoff exponent uses the attempt count we are ABOUT to record (current + 1),
     * matching DeadLetterReprocessor's post-increment backoff.
     */
    private void recordFailure(PendingApproval approval, String error) {
        long attemptsAfter = (long) approval.getReconcileAttempts() + 1;
        long backoffMinutes = Math.min((long) Math.pow(2, attemptsAfter), MAX_BACKOFF_MINUTES);
        Instant nextReconcileAt = Instant.now().plus(Duration.ofMinutes(backoffMinutes));
        approvalService.recordReconcileFailure(
                approval.getId(), approval.getTenantId(), nextReconcileAt, error);
        log.warn("Refund re-drive failed approval={} tenant={} attempts={} nextRetry={}: {}",
                approval.getId(), approval.getTenantId(), attemptsAfter, nextReconcileAt, error);
    }

    /**
     * Operator-signal sweep — every 15 min by default. Surfaces the attempts-EXHAUSTED tail (APPROVED
     * refunds out of attempts, still unexecuted) at ERROR so a human can intervene; these are excluded
     * from the re-drive finder so the PSP is not hammered, but must never be silently stranded.
     *
     * <p>Guarded by a cross-instance lock under a DISTINCT name ({@code refund-reconcile-signal}) from
     * the re-drive cycle, so on N replicas only ONE emits the ERROR signal per cycle instead of N
     * duplicate pages (B-022 FIX 3). The lock fails CLOSED (Valkey down → skip), which self-heals next
     * tick — an occasional missed sweep is acceptable because the SAME exhausted rows are re-selected
     * the next cycle. The sweep stays READ-ONLY: status is never flipped, so each row remains
     * hand-recoverable (clearing reconcile_attempts re-drives the SAME idempotency key safely).</p>
     */
    @SystemTransactional
    @Scheduled(fixedDelayString = "${nexuspay.refund.reconciler.signal-delay-ms:900000}")
    public void signalExhaustedRefunds() {
        schedulerLock.runExclusively(SIGNAL_LOCK_NAME, LOCK_TTL, this::doSignalExhausted);
    }

    /** Read-only operator-signal body; runs only on the replica that wins {@link #SIGNAL_LOCK_NAME}. */
    void doSignalExhausted() {
        List<PendingApproval> exhausted = approvalService.findExhaustedRefunds(maxAttempts);
        if (exhausted.isEmpty()) {
            return;
        }
        log.error("OPERATOR SIGNAL: {} APPROVED refund(s) exhausted {} reconcile attempts and remain "
                + "UNEXECUTED — manual intervention required", exhausted.size(), maxAttempts);
        for (PendingApproval a : exhausted) {
            log.error("  stuck refund approval={} tenant={} paymentId={} lastError=\"{}\"",
                    a.getId(), a.getTenantId(),
                    a.getPayload() != null ? a.getPayload().get("payment_id") : null,
                    a.getLastReconcileError());
        }
    }
}
