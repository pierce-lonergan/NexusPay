package io.nexuspay.dispute.application.port.out;

/**
 * Output port for creating chargeback-related ledger entries.
 *
 * <p>When a dispute transitions state, ledger entries must be created:
 * <ul>
 *   <li>OPENED: reserve chargeback amount (DR reserve, CR merchant receivables)</li>
 *   <li>WON: reverse reservation (DR merchant receivables, CR reserve)</li>
 *   <li>LOST: finalise as expense (DR chargeback expense, CR reserve)</li>
 * </ul>
 *
 * @since 0.2.4 (Sprint 2.4)
 */
public interface LedgerPort {

    /**
     * Creates a chargeback reserve journal entry when a dispute is opened.
     *
     * @param tenantId   the dispute's server-authoritative tenant (from the
     *                   HMAC-verified webhook, SEC-BATCH-2); the journal entry +
     *                   postings and account stamping are scoped to this tenant
     * @param disputeId  the dispute identifier (for payment_reference)
     * @param amount     minor units to reserve
     * @param currency   ISO 4217 currency code
     */
    void createChargebackReserve(String tenantId, String disputeId, long amount, String currency);

    /**
     * Reverses the chargeback reserve when a dispute is won.
     *
     * @param tenantId   the dispute's server-authoritative tenant (from the
     *                   HMAC-verified webhook, SEC-BATCH-2); must match the tenant
     *                   under which the original reserve was posted
     * @param disputeId  the dispute identifier (for payment_reference)
     * @param amount     minor units to release
     * @param currency   ISO 4217 currency code
     */
    void reverseChargebackReserve(String tenantId, String disputeId, long amount, String currency);

    /**
     * Finalises the chargeback as an expense when a dispute is lost.
     *
     * @param tenantId   the dispute's server-authoritative tenant (from the
     *                   HMAC-verified webhook, SEC-BATCH-2); must match the tenant
     *                   under which the original reserve was posted
     * @param disputeId  the dispute identifier (for payment_reference)
     * @param amount     minor units to expense
     * @param currency   ISO 4217 currency code
     */
    void finaliseChargebackExpense(String tenantId, String disputeId, long amount, String currency);
}
