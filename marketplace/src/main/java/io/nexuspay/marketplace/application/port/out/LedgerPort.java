package io.nexuspay.marketplace.application.port.out;

import java.util.List;

/**
 * Output port for booking split-payment journal entries (GAP-063).
 *
 * <p>Marketplace money movement must be visible to the double-entry ledger. When a split payment
 * is created (the only real money transition — {@code SplitPaymentStatus.COMPLETED} has no
 * transition anywhere in the codebase today), a single balanced journal entry is posted:</p>
 *
 * <ul>
 *   <li>DR {@code la_platform_clearing_{ccy}} — the SUM of all credits below (composed as the sum
 *       so the entry balances by construction even when FIXED rules under-allocate the total);</li>
 *   <li>CR {@code la_connected_payable_{ccy}} — one credit PER split leg (leg identity is carried
 *       in entry metadata, since ledger accounts are per-currency singletons);</li>
 *   <li>CR {@code la_platform_fee_revenue_{ccy}} — the platform fee, when {@code fee > 0}.</li>
 * </ul>
 *
 * <p><b>CARDINAL RULE — the ledger is the money truth, not a best-effort cache.</b> This method
 * MUST be called INSIDE the split-creation transaction ({@code SplitPaymentWriter.create}'s
 * REQUIRES_NEW tx): a posting failure must fail (roll back) the split creation itself. Never wrap
 * the call in try/catch-swallow, never {@code @Async} it, never isolate it in its own transaction.
 * (This is deliberately the OPPOSITE of the GAP-076 best-effort projection pattern.)</p>
 *
 * <p>Idempotency: the entry is keyed by {@code (paymentReference=splitPaymentId,
 * description="Split payment created")} under the V4028 unique index
 * {@code uq_journal_entries_payment_ref_desc}, so a retried/racing create can never double-book
 * (the SEC-20/V4034 split idempotency is the primary layer; the index is the backstop).</p>
 *
 * @since GAP-063 (WAVE1-money-ledger)
 */
public interface LedgerPort {

    /**
     * Books the balanced split-distribution entry for a newly created split payment.
     *
     * @param tenantId          the caller's server-authoritative tenant (from the authenticated
     *                          principal — SEC-BATCH-1); stamps both the auto-created accounts and
     *                          the journal entry/postings
     * @param splitPaymentId    the split payment id ({@code payment_reference} / idempotency key part)
     * @param paymentId         the originating payment id (metadata)
     * @param currency          ISO 4217 currency code
     * @param legs              the resolved per-connected-account distribution legs (calculated
     *                          minor-unit amounts, post fee deduction)
     * @param platformFeeAmount the platform fee in minor units ({@code 0} = no fee line)
     * @param livemode          whether the caller operates in live mode (metadata only — journal
     *                          entries carry no livemode column; dispute precedent)
     */
    void postSplitDistribution(String tenantId, String splitPaymentId, String paymentId,
                               String currency, List<Leg> legs, long platformFeeAmount,
                               boolean livemode);

    /** One resolved split leg: the connected account credited and its calculated minor-unit amount. */
    record Leg(String connectedAccountId, long amount) {}
}
