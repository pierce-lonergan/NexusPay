package io.nexuspay.payment.application.port.out;

import io.nexuspay.payment.domain.paymentmethod.PaymentMethod;

import java.util.List;
import java.util.Optional;

/**
 * Output port for saved payment-method persistence (TEST-3b). Mirrors the
 * {@link CustomerRepository} contract.
 *
 * @since TEST-3b
 */
public interface PaymentMethodRepository {

    PaymentMethod save(PaymentMethod paymentMethod);

    /**
     * SEC-26: tenant-scoped by-id lookup for REST retrieve/detach. Returns the method only when it
     * belongs to {@code tenantId} AND is not soft-deleted; an absent, foreign-tenant, OR soft-deleted
     * id all yield an empty Optional so the caller cannot distinguish "does not exist" from "belongs to
     * another tenant" (no cross-tenant existence oracle). The predicate is pushed to SQL so a
     * foreign-tenant row never leaves the DB. This is the ONLY by-id finder — there is no bypass.
     */
    Optional<PaymentMethod> findByIdAndTenantId(String id, String tenantId);

    /**
     * SEC-26: a customer's live (non-detached) saved methods for {@code GET
     * /v1/customers/{customerId}/payment_methods}, newest first, paginated by {@code limit}/{@code offset}.
     * Always paired with {@code tenantId} (tenant + {@code deleted_at IS NULL} pushed to SQL). The caller
     * resolves the customer tenant-scoped FIRST so a foreign customerId 404s (not an empty list).
     */
    List<PaymentMethod> findByCustomerAndTenant(String customerId, String tenantId, int limit, int offset);
}
