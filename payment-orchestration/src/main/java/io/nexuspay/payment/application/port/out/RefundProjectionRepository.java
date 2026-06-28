package io.nexuspay.payment.application.port.out;

import io.nexuspay.payment.domain.projection.RefundProjectionRow;

import java.util.List;

/**
 * GAP-076 (critique v3 F1): out port for the refunds READ-MODEL projection. Mirrors
 * {@link PaymentProjectionRepository}: idempotent upsert by refund-id PK with a monotonic
 * (pending &lt; succeeded/failed) status-precedence guard, and a TENANT + LIVEMODE-scoped finder only
 * (no unscoped finder).
 */
public interface RefundProjectionRepository {

    /**
     * Idempotently upserts the refund projection row by its refund-id PK. UPDATE advances status only when
     * the new rank is &ge; the existing rank and never overwrites a terminal (succeeded/failed) status;
     * {@code created_at} is set once and never overwritten.
     */
    void upsert(RefundProjectionRow row);

    /**
     * GAP-076 async-live settlement: advances the status of an EXISTING refund projection row
     * (precedence-guarded), a NO-OP when no row exists. Mirrors
     * {@code PaymentProjectionRepository.updateStatusIfExists}.
     *
     * @param refundId the projection row's PK
     * @param status   the target status (a {@code RefundResponse.STATUS_*} value)
     */
    void updateStatusIfExists(String refundId, String status);

    /**
     * Tenant + livemode-scoped enumeration backing {@code GET /v1/refunds}, newest first.
     *
     * @param tenantId      the caller's principal tenant — ALWAYS supplied
     * @param livemode      the caller key's mode (scopes the list to test vs live)
     * @param paymentFilter optional exact payment_id filter (nullable = no payment filter)
     * @param statusFilter  optional exact status filter (nullable = no status filter)
     * @param limit         page size (already clamped to [1,100])
     * @param offset        row offset (already clamped to &ge; 0)
     */
    List<RefundProjectionRow> listByTenant(String tenantId, boolean livemode, String paymentFilter,
                                           String statusFilter, int limit, int offset);

    /**
     * GAP-077 (critique v3 F4): the TEST-mode refund ids for a tenant — {@code SELECT refund_id WHERE
     * tenant_id = ? AND livemode = false}. BOTH predicates always present. Used by
     * {@code SandboxResetService} to collect CONFIRMED-tenant refund ids BEFORE the delete so the in-memory
     * mock refund map is cleared for exactly those ids. Live ids are NEVER returned.
     *
     * @param tenantId the caller's principal tenant
     * @return the test-mode refund ids belonging to {@code tenantId} (possibly empty, never null)
     */
    List<String> findTestIds(String tenantId);

    /**
     * GAP-077 (critique v3 F4): hard-deletes the tenant's TEST refund rows — {@code DELETE WHERE tenant_id =
     * ? AND livemode = false}. BOTH predicates inseparable; no half-scoped variant. Returns the deleted
     * count. Deleted FIRST in the reset (logical child of payments). Must run inside the reset
     * {@code @Transactional}.
     *
     * @param tenantId the caller's principal tenant
     * @return the number of test refund rows deleted for {@code tenantId}
     */
    int deleteTestRows(String tenantId);
}
