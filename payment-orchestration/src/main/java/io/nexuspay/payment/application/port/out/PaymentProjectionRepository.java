package io.nexuspay.payment.application.port.out;

import io.nexuspay.payment.domain.projection.PaymentProjectionRow;

import java.util.List;

/**
 * GAP-076 (critique v3 F1): out port for the payments READ-MODEL projection.
 *
 * <p>The projection is a best-effort, READ-ONLY denormalization. {@link #upsert(PaymentProjectionRow)}
 * is idempotent by the payment-id PK and applies a monotonic status-precedence guard so an out-of-order
 * write never regresses a terminal status. The list finder is TENANT + LIVEMODE scoped — there is NO
 * unscoped finder (no {@code findById}, no tenant-only-without-livemode), so a foreign tenant's rows can
 * never be enumerated (no IDOR, no count leak).</p>
 */
public interface PaymentProjectionRepository {

    /**
     * Idempotently upserts the projection row by its payment-id PK.
     *
     * <ul>
     *   <li>INSERT when no row exists for the id;</li>
     *   <li>UPDATE the mutable columns (status, amount, currency, capture_method, customer_id,
     *       connector_name, error_code, error_message, updated_at) when a row already exists — but ONLY
     *       advancing status when the new status's precedence rank is &ge; the existing rank, and NEVER
     *       overwriting a terminal status (succeeded/failed/cancelled) with another status. {@code
     *       created_at} is set once (from the first write) and never overwritten.</li>
     * </ul>
     *
     * <p>This protects the sync(create)+webhook(settlement) race + any webhook reordering: a late-arriving
     * {@code processing} cannot regress a {@code succeeded} row.</p>
     */
    void upsert(PaymentProjectionRow row);

    /**
     * GAP-076 async-live settlement: advances the status of an EXISTING projection row (precedence-guarded),
     * a NO-OP when no row exists for the id. Used by the HyperSwitch webhook hook so a forward-fill gap (a
     * payment born before the read-model shipped) is not back-filled with a partial 0-amount row.
     *
     * @param paymentId the projection row's PK
     * @param tenantId  the resolved trusted tenant (used to keep the row's stamp consistent; the existing
     *                  row's tenant is authoritative)
     * @param status    the target status (a {@code PaymentResponse.STATUS_*} value)
     * @param livemode  the resolved livemode
     */
    void updateStatusIfExists(String paymentId, String tenantId, String status, boolean livemode);

    /**
     * Tenant + livemode-scoped enumeration backing {@code GET /v1/payments}, newest first.
     *
     * @param tenantId       the caller's principal tenant — ALWAYS supplied, the only tenant ever queried
     * @param livemode       the caller key's mode — a test key lists only livemode=false rows, vice-versa
     * @param statusFilter   optional exact status filter (nullable = no status filter)
     * @param customerFilter optional exact customer_id filter (nullable = no customer filter)
     * @param limit          page size (already clamped to [1,100] by the query service)
     * @param offset         row offset (already clamped to &ge; 0 by the query service)
     */
    List<PaymentProjectionRow> listByTenant(String tenantId, boolean livemode, String statusFilter,
                                            String customerFilter, int limit, int offset);
}
