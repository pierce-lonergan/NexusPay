package io.nexuspay.b2b.application.port.out;

/**
 * Output port for booking B2B money-transition journal entries (GAP-069).
 *
 * <p>The B2B accrual→settlement lifecycle and its postings:</p>
 * <ul>
 *   <li><b>Invoice paid</b> ({@link #postInvoicePaid}): DR {@code la_accounts_payable_{ccy}},
 *       CR {@code la_cash_clearing_{ccy}} for {@code amount + taxAmount}. (Documented
 *       simplification: there is no accrual entry at invoice SENT, so accounts payable is settled
 *       without a prior accrual and trends net-debit; an accrual-at-recognition follow-up would
 *       not change this entry's idempotency key.) The linked purchase order's {@code markPaid}
 *       posts NOTHING — the invoice entry IS the money record; a PO-side entry would double-count
 *       the same cash movement.</li>
 *   <li><b>Vendor payment approved</b> ({@link #postVendorPaymentApproved}): DR
 *       {@code la_vendor_expense_{ccy}}, CR {@code la_vendor_payable_{ccy}} — the accrual. The
 *       approval is the moment the liability is recognized, which is exactly the transition
 *       maker-checker (GAP-068) now gates.</li>
 *   <li><b>Vendor payment disbursed</b> ({@link #postVendorPaymentDisbursed}): DR
 *       {@code la_vendor_payable_{ccy}}, CR {@code la_cash_clearing_{ccy}} — the settlement,
 *       keyed off the CONFIRMED execution-stub result (its deterministic
 *       {@code externalReference}), never off intent. (Deliberate reorientation of the original
 *       task sketch "DR cash clearing, CR vendor payable": debiting cash on an OUTBOUND
 *       disbursement would book the money backwards; with the accrual above, vendor payable and
 *       cash both net correctly over the lifecycle.)</li>
 *   <li><b>Purchase-order approval posts NOTHING — deliberately.</b> An approved PO is an
 *       executory commitment: neither party has performed, no cash has moved, and no asset or
 *       liability exists to recognize. Booking one would be encumbrance accounting (a
 *       governmental-fund practice) — fake accounting on a payments-platform GL. PO approval is
 *       an authorization control only (maker-checker gated above threshold); first GL recognition
 *       happens at invoice payment / vendor-payment approval. Do NOT "helpfully" add a PO entry.</li>
 * </ul>
 *
 * <p><b>CARDINAL RULE — the ledger is the money truth, not a best-effort cache.</b> Every method
 * MUST be called INSIDE the owning service's state-transition transaction: a posting failure must
 * fail (roll back) the transition. Never try/catch-swallow, never {@code @Async}, never isolate in
 * REQUIRES_NEW. (Opposite of the GAP-076 best-effort projection pattern.)</p>
 *
 * <p>Idempotency: each entry is keyed by {@code (paymentReference=<entityId>,
 * description=<transition name>)} under the V4028 unique index — the SEC-BATCH-4/SEC-10 mechanism
 * reused unchanged. The PRIMARY no-double-book layer is the domain state guard reached before any
 * posting ({@code B2bInvoice.markPaid}, {@code VendorPayment.approve}, the iam atomic
 * PENDING→APPROVED claim); the index is the concurrency backstop.</p>
 *
 * @since GAP-069 (WAVE1-money-ledger)
 */
public interface LedgerPort {

    /**
     * Books the invoice-payment entry: DR accounts payable, CR cash clearing.
     *
     * <p>Zero-total contract: a ZERO-total invoice (reachable via a PO with no line items and tax
     * 0) moves no money and books NOTHING — the caller ({@code B2bInvoiceService.markInvoicePaid})
     * skips this call for {@code totalAmount == 0}. This method itself must only ever be invoked
     * with {@code totalAmount > 0} (a 0-amount posting line is unconstructible by design).</p>
     *
     * @param tenantId    the caller's server-authoritative tenant (SEC-BATCH-1)
     * @param invoiceId   the invoice id ({@code payment_reference} / idempotency key part)
     * @param totalAmount invoice amount + tax, minor units, strictly positive
     * @param currency    ISO 4217 currency code
     * @param livemode    the caller's key mode ({@code CallerMode.isLive()}) — recorded in entry
     *                    metadata so sandbox postings are distinguishable from live money
     *                    (marketplace-edge mirror; journal entries carry no livemode column)
     */
    void postInvoicePaid(String tenantId, String invoiceId, long totalAmount, String currency,
                         boolean livemode);

    /**
     * Books the vendor-payment ACCRUAL at approval: DR vendor expense, CR vendor payable.
     *
     * @param tenantId  the caller's server-authoritative tenant
     * @param paymentId the vendor payment id
     * @param amount    minor units (strictly positive — enforced by {@code @Positive} at the HTTP
     *                  boundary on vendor-payment creation)
     * @param currency  ISO 4217 currency code
     * @param livemode  the caller's key mode — recorded in entry metadata (see
     *                  {@link #postInvoicePaid})
     */
    void postVendorPaymentApproved(String tenantId, String paymentId, long amount, String currency,
                                   boolean livemode);

    /**
     * Books the vendor-payment SETTLEMENT after the execution rail CONFIRMS the disbursement:
     * DR vendor payable, CR cash clearing. Keyed off the confirmed stub result — the
     * {@code externalReference} travels in entry metadata.
     *
     * @param tenantId          the caller's server-authoritative tenant
     * @param paymentId         the vendor payment id
     * @param externalReference the execution rail's deterministic confirmation reference
     * @param amount            minor units (strictly positive)
     * @param currency          ISO 4217 currency code
     * @param livemode          the caller's key mode — recorded in entry metadata (see
     *                          {@link #postInvoicePaid})
     */
    void postVendorPaymentDisbursed(String tenantId, String paymentId, String externalReference,
                                    long amount, String currency, boolean livemode);
}
