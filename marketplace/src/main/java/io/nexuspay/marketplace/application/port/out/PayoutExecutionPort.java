package io.nexuspay.marketplace.application.port.out;

import io.nexuspay.marketplace.domain.PayoutMethod;

/**
 * Outbound port for executing payout disbursements to connected accounts.
 * Abstracts over bank transfer and card push execution providers.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface PayoutExecutionPort {

    /**
     * Executes a payout disbursement to the connected account's bank or card.
     *
     * <p><b>SEC-25 hard-timeout contract (GAP-062 adapter MUST honour).</b> The {@code PayoutReconciler}
     * re-drives stuck payouts INSIDE a tenant-bound transaction that holds a {@code FOR UPDATE} row lock
     * (the {@code reloadStuckForUpdate} guard) across this call, so a slow/hung PSP pins a pooled DB
     * connection + row lock for the duration of the round-trip. The real GAP-062 implementation MUST
     * therefore enforce a HARD client read/connect timeout WELL UNDER the reconciler's
     * {@code LOCK_TTL = 5 min} (target a few seconds, single-digit at most) so a PSP stall surfaces as a
     * thrown transient exception (recorded + re-drivable next cycle) long before the lease can lapse.
     * A timeout-or-error throw is the correct outcome here: the deterministic idempotency key guarantees
     * the eventual retry is deduped (no double-pay).</p>
     */
    PayoutExecutionResult execute(PayoutExecutionRequest request);

    record PayoutExecutionRequest(
            String payoutId,
            String connectedAccountId,
            long amount,
            String currency,
            PayoutMethod method,
            // SEC-25: deterministic PSP idempotency key ("payout-<payoutId>", Payout.idempotencyKey).
            // The original disburse AND every reconciler re-drive send the byte-identical key, so a
            // re-drive of a crashed-mid-disburse payout is deduped by the PSP (B-009) — money moves
            // exactly once, NO double-pay. The real GAP-062 adapter forwards this as the PSP's
            // Idempotency-Key header; the stub derives a stable externalReference from it.
            String idempotencyKey
    ) {}

    record PayoutExecutionResult(
            boolean success,
            String externalReference,
            String failureReason
    ) {}
}
