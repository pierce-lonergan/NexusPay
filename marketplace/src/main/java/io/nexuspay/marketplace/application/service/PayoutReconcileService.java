package io.nexuspay.marketplace.application.service;

import io.nexuspay.marketplace.application.port.out.MarketplaceEventPublisher;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.application.port.out.PayoutExecutionPort;
import io.nexuspay.marketplace.domain.Payout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SEC-25: application service backing the {@code PayoutReconciler}. Keeps the reconciler thin and
 * concentrates the conditional-UPDATE semantics (status='PROCESSING' gating, tenant binding,
 * attempt bookkeeping) in one place — the marketplace mirror of iam's {@code ApprovalService} seam
 * used by B-022's {@code RefundReconciler}.
 *
 * <p>The re-drive ({@link #redrive}) calls {@code PayoutExecutionPort.execute} with the SAME
 * deterministic idempotency key {@code Payout.idempotencyKey(id)} the ORIGINAL disburse used, so a
 * re-drive of a payout that crashed mid-disbursement is deduped by the PSP (B-009) — money moves
 * exactly once. All writes are conditional UPDATEs (PROCESSING + tenant), so two writers can never
 * both finalize a row.</p>
 *
 * <p>Discovery ({@link #findStuckProcessing} / {@link #findExhaustedProcessing}) is cross-tenant and
 * MUST be called from a {@code @SystemTransactional} (BYPASSRLS) context (the reconciler's scheduled
 * method). The per-payout terminal/bookkeeping writes MUST be called bound to the row's own tenant
 * (via {@code TenantWorkRunner.callInTenant}) so RLS WITH CHECK on payouts (V4020) admits them.</p>
 */
@Service
public class PayoutReconcileService {

    private static final Logger log = LoggerFactory.getLogger(PayoutReconcileService.class);
    // last_reconcile_error is TEXT (V4032:34, unbounded) — this generous cap just bounds log/row bloat.
    private static final int MAX_ERROR_LEN = 480;
    // SEC-25: payouts.failure_reason is VARCHAR(256) (V4002:68). The terminal FAILED reason MUST be
    // capped to the column width, NOT MAX_ERROR_LEN: a 256<len<=480 PSP failureReason would otherwise
    // pass truncate() uncapped and make the markFailedById UPDATE throw a value-too-long DataException,
    // so the row could never reach the FAILED terminal state and would burn every reconcile attempt.
    private static final int FAILURE_REASON_MAX_LEN = 256;

    private final MarketplaceRepository repository;
    private final PayoutExecutionPort payoutExecution;
    private final MarketplaceEventPublisher eventPublisher;

    public PayoutReconcileService(MarketplaceRepository repository,
                                  PayoutExecutionPort payoutExecution,
                                  MarketplaceEventPublisher eventPublisher) {
        this.repository = repository;
        this.payoutExecution = payoutExecution;
        this.eventPublisher = eventPublisher;
    }

    // --- cross-tenant discovery (call under @SystemTransactional) -------------------------------

    /**
     * The re-drivable set: PROCESSING payouts claimed before {@code cutoff}, not attempt-exhausted,
     * past their backoff gate. Cross-tenant.
     */
    @Transactional(readOnly = true)
    public List<Payout> findStuckProcessing(Instant cutoff, Instant now, int maxAttempts, int batchSize) {
        return repository.findStuckProcessingPayouts(cutoff, now, maxAttempts, batchSize);
    }

    /** Operator-signal query: PROCESSING payouts that exhausted reconcile attempts. Cross-tenant. */
    @Transactional(readOnly = true)
    public List<Payout> findExhaustedProcessing(int maxAttempts) {
        return repository.findExhaustedProcessingPayouts(maxAttempts);
    }

    // --- per-payout re-drive + terminal transitions (call bound to the row's tenant) -----------

    /**
     * Intra-cycle guard: re-load FOR UPDATE; present only if STILL PROCESSING (empty if the original
     * cycle or a concurrent pass already finalized it).
     */
    @Transactional
    public Optional<Payout> reloadStuckForUpdate(String payoutId) {
        return repository.reloadStuckPayoutForUpdate(payoutId);
    }

    /**
     * Re-drives the disbursement with the SAME deterministic key the original used — the PSP dedups,
     * so this is safe even if the original actually settled (no double-pay). May throw if the gateway
     * is unavailable (transient) — the caller records a bounded failure and leaves the row PROCESSING.
     */
    public PayoutExecutionPort.PayoutExecutionResult redrive(Payout p) {
        return payoutExecution.execute(new PayoutExecutionPort.PayoutExecutionRequest(
                p.getId(), p.getConnectedAccountId(), p.getAmount(), p.getCurrency(), p.getMethod(),
                Payout.idempotencyKey(p.getId())));
    }

    /**
     * Terminal: PROCESSING -> PAID (conditional UPDATE). Returns true iff THIS call flipped the row,
     * in which case the PayoutPaid event is published (mirrors PayoutService). Bound to the row tenant.
     */
    @Transactional
    public boolean markPaid(String payoutId, String tenantId, String externalReference) {
        boolean flipped = repository.markPayoutPaid(payoutId, tenantId, externalReference);
        if (flipped) {
            eventPublisher.publishEvent("Payout", payoutId, "PayoutPaid",
                    Map.of("externalReference", externalReference, "tenantId", tenantId), tenantId);
            log.info("SEC-25 payout reconciled -> PAID: id={}, tenant={}, ref={}",
                    payoutId, tenantId, externalReference);
        }
        return flipped;
    }

    /**
     * Terminal: PROCESSING -> FAILED (terminal PSP failure; conditional UPDATE). Publishes PayoutFailed
     * iff THIS call flipped the row. Bound to the row tenant.
     */
    @Transactional
    public boolean markFailed(String payoutId, String tenantId, String reason) {
        // Cap to the failure_reason column width (256) — NOT MAX_ERROR_LEN (480) — so the terminal
        // UPDATE can never throw value-too-long and strand the row in PROCESSING (SEC-25 SHOULD_FIX).
        boolean flipped = repository.markPayoutFailed(payoutId, tenantId, truncate(reason, FAILURE_REASON_MAX_LEN));
        if (flipped) {
            eventPublisher.publishEvent("Payout", payoutId, "PayoutFailed",
                    Map.of("reason", String.valueOf(reason), "tenantId", tenantId), tenantId);
            log.warn("SEC-25 payout reconciled -> FAILED: id={}, tenant={}, reason={}",
                    payoutId, tenantId, reason);
        }
        return flipped;
    }

    /**
     * Transient-failure bookkeeping: bump attempts, set the backoff gate + last error, LEAVE the row
     * PROCESSING (re-drivable next cycle). Bound to the row tenant.
     */
    @Transactional
    public void recordFailure(String payoutId, String tenantId, Instant nextReconcileAt, String error) {
        // last_reconcile_error is TEXT (unbounded); the 480 cap only bounds row/log bloat.
        repository.recordPayoutReconcileFailure(payoutId, tenantId, nextReconcileAt, truncate(error, MAX_ERROR_LEN));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
