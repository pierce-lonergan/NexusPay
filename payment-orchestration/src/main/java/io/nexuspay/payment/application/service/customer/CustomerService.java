package io.nexuspay.payment.application.service.customer;

import io.nexuspay.common.metadata.MetadataSanitizer;
import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.payment.application.port.out.CustomerRepository;
import io.nexuspay.payment.domain.customer.Customer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates the customer CRUD lifecycle (TEST-3a). Mirrors {@code DisputeLifecycleService}'s
 * tenant-scoped, no-oracle access pattern.
 *
 * <p>Every by-id read/mutation is scoped to the caller's tenant via
 * {@link CustomerRepository#findByIdAndTenantId}. There is deliberately NO unscoped {@code findById}
 * exposed (customers have no server-authoritative webhook path that needs one), so there is no bypass.</p>
 *
 * @since TEST-3a
 */
@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    /**
     * Creates a customer under the caller's tenant. {@code livemode} is server-derived from the caller
     * key mode (NEVER client-supplied); the id is minted server-side via {@code PrefixedId.customer()}.
     *
     * <p>PII/DATA-LIFECYCLE: the client-supplied {@code metadata} is routed through
     * {@link MetadataSanitizer#sanitize} before it is ever persisted — PAN/card keys (card/number/cvc/cvv/
     * pan/payment_method_data), gate-owned authority markers, and any {@code __}-prefixed server-reserved
     * control key (e.g. {@code __livemode}) are dropped at any depth, and an over-{@link
     * MetadataSanitizer#MAX_KEYS} map is dropped to {@code {}}. This is the SAME rule the webhook
     * correlation surface uses, so a reserved-key smuggle never lands in the saved-credential anchor.</p>
     */
    @Transactional
    public Customer create(String tenantId, boolean livemode, String email, String name,
                           String description, Map<String, Object> metadata) {
        // null = no metadata supplied (stays null); only sanitize a map the caller actually supplied.
        Map<String, Object> safeMetadata = metadata != null ? MetadataSanitizer.sanitize(metadata) : null;
        Customer customer = Customer.create(tenantId, livemode, email, name, description, safeMetadata);
        customer = customerRepository.save(customer);
        log.info("Customer created: id={}, tenant={}, livemode={}", customer.getId(), tenantId, livemode);
        return customer;
    }

    /**
     * SEC-26: tenant-scoped by-id lookup for {@code GET /v1/customers/{id}}. Returns the customer only
     * when it belongs to {@code tenantId} and is not soft-deleted; an absent, foreign-tenant, OR
     * soft-deleted id yields an empty Optional so the controller 404s identically for all (no oracle).
     * {@code tenantId} is the authenticated caller's tenant (CallerTenant.require()), never a header.
     */
    public Optional<Customer> findById(String id, String tenantId) {
        return customerRepository.findByIdAndTenantId(id, tenantId);
    }

    /**
     * SEC-26: tenant-scoped enumeration for {@code GET /v1/customers}. Returns only the caller tenant's
     * live (non-soft-deleted) customers.
     */
    public List<Customer> listByTenant(String tenantId, int limit, int offset) {
        return customerRepository.findByTenant(tenantId, limit, offset);
    }

    /**
     * Updates the mutable fields (email/name/description/metadata) of a customer the caller owns.
     * Resolves via {@link #getOrThrow} so a tenant-A caller cannot update (or probe the existence of) a
     * tenant-B customer — a foreign/absent/soft-deleted id 404s (no oracle). {@code livemode} is NOT
     * mutable.
     *
     * <p>PII/DATA-LIFECYCLE: a SUPPLIED {@code metadata} map (non-null) is routed through
     * {@link MetadataSanitizer#sanitize} before persist — same strip as {@link #create} — so the smuggle
     * is sealed on the update path too, not only at create. A {@code null} {@code metadata} means
     * "leave unchanged" and is passed through untouched (NOT sanitized to {@code {}}), preserving the
     * partial-update semantics of {@link Customer#applyUpdate}.</p>
     */
    @Transactional
    public Customer update(String id, String tenantId, String email, String name,
                           String description, Map<String, Object> metadata) {
        Customer customer = getOrThrow(id, tenantId);
        // null = leave metadata unchanged; only sanitize a map the caller actually supplied.
        Map<String, Object> safeMetadata = metadata != null ? MetadataSanitizer.sanitize(metadata) : null;
        customer.applyUpdate(email, name, description, safeMetadata);
        customer = customerRepository.save(customer);
        log.info("Customer updated: id={}, tenant={}", id, tenantId);
        return customer;
    }

    /**
     * SOFT-deletes a customer the caller owns (sets {@code deleted_at}). Resolves via {@link #getOrThrow}
     * so a tenant-A caller cannot delete (or probe) a tenant-B customer — a foreign/absent/already-deleted
     * id 404s (no oracle). After deletion the customer no longer appears in retrieve/list.
     */
    @Transactional
    public Customer delete(String id, String tenantId) {
        Customer customer = getOrThrow(id, tenantId);
        customer.markDeleted();
        customer = customerRepository.save(customer);
        log.info("Customer soft-deleted: id={}, tenant={}", id, tenantId);
        return customer;
    }

    // -- Helpers --

    /**
     * SEC-26: tenant-scoped fetch-or-404 for REST mutations. Pairs the tenant-scoped finder with
     * {@link TenantOwnership#require} so a tenant-A caller cannot read/mutate a tenant-B customer by id.
     * Returns 404 (ResourceNotFoundException, via TenantOwnership) on a foreign/absent/soft-deleted id —
     * no existence oracle.
     */
    private Customer getOrThrow(String id, String tenantId) {
        return TenantOwnership.require(
                customerRepository.findByIdAndTenantId(id, tenantId), "Customer");
    }
}
