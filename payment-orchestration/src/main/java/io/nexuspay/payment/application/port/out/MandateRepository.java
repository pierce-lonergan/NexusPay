package io.nexuspay.payment.application.port.out;

import io.nexuspay.payment.domain.mandate.Mandate;

import java.util.List;
import java.util.Optional;

/**
 * Output port for mandate / consent persistence (TEST-3d). Mirrors the {@link PaymentMethodRepository}
 * contract.
 *
 * @since TEST-3d
 */
public interface MandateRepository {

    Mandate save(Mandate mandate);

    /**
     * SEC-26: tenant-scoped by-id lookup for REST retrieve/revoke AND the off-session charge gate. Returns
     * the mandate only when it belongs to {@code tenantId}; an absent OR foreign-tenant id yields an empty
     * Optional so the caller cannot distinguish "does not exist" from "belongs to another tenant" (no
     * cross-tenant existence oracle). The predicate is pushed to SQL so a foreign-tenant row never leaves
     * the DB. This is the ONLY by-id finder — there is no bypass.
     *
     * <p>NOTE — the key divergence from the payment_methods template: there is deliberately NO
     * {@code deleted_at IS NULL} (or status) filter. A revoked (INACTIVE) mandate MUST stay retrievable, so
     * this finder returns it unchanged with status INACTIVE.</p>
     */
    Optional<Mandate> findByIdAndTenantId(String id, String tenantId);

    /**
     * SEC-26: the caller tenant's mandates for {@code GET /v1/mandates}, newest first, paginated by
     * {@code limit}/{@code offset}. Tenant-scoped (the predicate is pushed to SQL). Enumerates ALL of the
     * tenant's mandates including revoked (INACTIVE) ones (no soft-delete exclusion).
     */
    List<Mandate> findByTenant(String tenantId, int limit, int offset);

    /**
     * GAP-077 (critique v3 F4): HARD-deletes the tenant's TEST mandates — {@code DELETE WHERE tenant_id = ?
     * AND livemode = false}. BOTH predicates inseparable; no half-scoped variant. Returns the deleted count.
     *
     * <p>NOTE — unlike the read finder, which deliberately keeps revoked (INACTIVE) mandates retrievable,
     * the reset deletes them too when {@code livemode=false}: a sandbox wipe purges ALL test mandates
     * regardless of status. Deleted before payment_methods/customers (logical child referencing pm_ + cus_).
     * Must run inside the reset {@code @Transactional}.</p>
     *
     * @param tenantId the caller's principal tenant
     * @return the number of test mandate rows deleted for {@code tenantId}
     */
    int deleteTestRows(String tenantId);
}
