package io.nexuspay.payment.application.port.out;

import io.nexuspay.payment.domain.customer.Customer;

import java.util.List;
import java.util.Optional;

/**
 * Output port for customer persistence (TEST-3a). Mirrors the dispute
 * {@code application.port.out.DisputeRepository} contract.
 *
 * @since TEST-3a
 */
public interface CustomerRepository {

    Customer save(Customer customer);

    /**
     * SEC-26: tenant-scoped by-id lookup for REST reads/mutations. Returns the customer only when it
     * belongs to {@code tenantId} AND is not soft-deleted; an absent, foreign-tenant, OR soft-deleted
     * customer all yield an empty Optional so the caller cannot distinguish "does not exist" from
     * "belongs to another tenant" (no cross-tenant existence oracle). The predicate is pushed to SQL so
     * a foreign-tenant row never leaves the DB.
     *
     * <p>This is the ONLY by-id finder exposed; unlike dispute, customers have no server-authoritative
     * webhook path that needs an unscoped {@code findById}, so there is no bypass.</p>
     */
    Optional<Customer> findByIdAndTenantId(String id, String tenantId);

    /**
     * SEC-26: tenant-scoped enumeration for {@code GET /v1/customers}. Returns only the caller tenant's
     * live (non-soft-deleted) customers, newest first, paginated by {@code limit}/{@code offset}.
     */
    List<Customer> findByTenant(String tenantId, int limit, int offset);

    /**
     * GAP-077 (critique v3 F4): HARD-deletes the tenant's TEST customers — {@code DELETE WHERE tenant_id = ?
     * AND livemode = false}. BOTH predicates inseparable in the single underlying query; no tenant-only
     * (would hit LIVE) or livemode-only (would cross tenants) variant. Returns the deleted count.
     *
     * <p>NOTE — this is a HARD delete, NOT the soft-delete convention used by {@link #findByIdAndTenantId}:
     * it ignores {@code deleted_at} and purges already-soft-deleted test rows too. That is the intended
     * sandbox-wipe semantics (a soft-deleted test customer is still tenant+test-scoped). Deleted LAST in the
     * reset (parent of payment_methods + mandates). Must run inside the reset {@code @Transactional}.</p>
     *
     * @param tenantId the caller's principal tenant
     * @return the number of test customer rows deleted for {@code tenantId}
     */
    int deleteTestRows(String tenantId);
}
