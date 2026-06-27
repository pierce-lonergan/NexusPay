package io.nexuspay.payment.adapter.in.rest;

import io.nexuspay.common.tenant.CallerMode;
import io.nexuspay.common.tenant.CallerTenant;
import io.nexuspay.payment.application.service.customer.CustomerService;
import io.nexuspay.payment.domain.customer.Customer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for the Customer resource (TEST-3a) — the anchor of the saved-credential cluster.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code POST   /v1/customers}      — create (201, {@code customers:write})</li>
 *   <li>{@code GET    /v1/customers/{id}} — retrieve ({@code customers:read}, 404 no-oracle)</li>
 *   <li>{@code GET    /v1/customers}      — list, tenant-scoped ({@code customers:read})</li>
 *   <li>{@code POST   /v1/customers/{id}} — update mutable fields ({@code customers:write})</li>
 *   <li>{@code DELETE /v1/customers/{id}} — soft delete ({@code customers:write})</li>
 * </ul>
 *
 * <h3>Tenant isolation (SEC-26)</h3>
 * <p>Every endpoint derives the tenant from the authenticated principal via
 * {@link CallerTenant#require()} — NEVER from a client {@code X-Tenant-Id} header or the request body
 * ({@link CreateCustomerRequest}/{@link UpdateCustomerRequest} carry NO tenant field). By-id reads and
 * mutations resolve the customer through the tenant-scoped service finders, so a foreign-tenant (or
 * soft-deleted) id 404s with no existence oracle, and the list endpoint enumerates only the caller's own
 * customers. Each method's {@code @PreAuthorize} AND-composes the role check with a {@code @scopeAuth.has}
 * scope check (fail-closed) — scopes NARROW the role, never widen it.</p>
 *
 * @since TEST-3a
 */
@RestController
@RequestMapping("/v1/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    /**
     * Creates a customer under the caller's tenant. {@code livemode} is server-derived from the caller
     * key mode ({@code CallerMode.isLive()}: test key -> false, live key -> true) — never client-supplied.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('customers:write')")
    public ResponseEntity<CustomerResponse> createCustomer(
            @RequestBody(required = false) CreateCustomerRequest request) {

        // SEC-26: tenant from the authenticated principal, never a client header/body.
        String tenantId = CallerTenant.require();
        // livemode server-derived from the caller key mode (INT-3); NOT from the request.
        boolean livemode = CallerMode.isLive();

        CreateCustomerRequest body = request != null ? request : new CreateCustomerRequest(null, null, null, null);
        Customer customer = customerService.create(
                tenantId, livemode, body.email(), body.name(), body.description(), body.metadata());

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(customer));
    }

    /**
     * Retrieves one customer by id. Tenant-scoped — a foreign-tenant (or soft-deleted) id 404s with no
     * existence oracle (same {@code .orElse(notFound())} idiom as {@code DisputeController.getDispute}).
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer') and @scopeAuth.has('customers:read')")
    public ResponseEntity<CustomerResponse> getCustomer(@PathVariable String id) {
        return customerService.findById(id, CallerTenant.require())
                .map(c -> ResponseEntity.ok(toResponse(c)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Lists customers for the current tenant (live, non-deleted), newest first.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer') and @scopeAuth.has('customers:read')")
    public ResponseEntity<List<CustomerResponse>> listCustomers(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        // SEC-26: list scoped to the AUTHENTICATED principal's tenant — a caller can only ever enumerate
        // their own tenant's customers.
        List<Customer> customers = customerService.listByTenant(CallerTenant.require(), limit, offset);
        return ResponseEntity.ok(customers.stream().map(this::toResponse).toList());
    }

    /**
     * Updates the mutable fields (email/name/description/metadata) of a customer the caller owns.
     * POST-to-id (Stripe-style, mirrors the dispute {@code POST /{id}/submit} idiom). A foreign/absent id
     * 404s via {@code TenantOwnership} -> {@code ResourceNotFoundException} (no oracle). {@code livemode}
     * is NOT mutable.
     */
    @PostMapping("/{id}")
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('customers:write')")
    public ResponseEntity<CustomerResponse> updateCustomer(
            @PathVariable String id,
            @RequestBody(required = false) UpdateCustomerRequest request) {

        UpdateCustomerRequest body = request != null ? request : new UpdateCustomerRequest(null, null, null, null);
        // SEC-26: tenant from the authenticated principal; the service resolves the customer via the
        // tenant-scoped finder (404 on a foreign id, no oracle) before applying the update.
        Customer customer = customerService.update(
                id, CallerTenant.require(), body.email(), body.name(), body.description(), body.metadata());
        return ResponseEntity.ok(toResponse(customer));
    }

    /**
     * Soft-deletes a customer the caller owns (sets {@code deleted_at}). A foreign/absent id 404s via
     * {@code TenantOwnership} (no oracle). Subsequent retrieve/list no longer return the row.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('customers:write')")
    public ResponseEntity<DeleteResponse> deleteCustomer(@PathVariable String id) {
        // SEC-26: tenant from the authenticated principal; soft delete scoped to the caller's tenant.
        Customer customer = customerService.delete(id, CallerTenant.require());
        return ResponseEntity.ok(new DeleteResponse(customer.getId(), "customer", true));
    }

    // ---- Response / Request DTOs ----

    private CustomerResponse toResponse(Customer c) {
        // tenant is NEVER exposed in the body. `created` is epoch seconds.
        return new CustomerResponse(
                c.getId(),
                "customer",
                c.isLivemode(),
                c.getEmail(),
                c.getName(),
                c.getDescription(),
                c.getMetadata(),
                c.getCreatedAt() != null ? c.getCreatedAt().getEpochSecond() : null);
    }

    record CustomerResponse(
            String id,
            String object,
            boolean livemode,
            String email,
            String name,
            String description,
            Map<String, Object> metadata,
            Long created
    ) {}

    record CreateCustomerRequest(
            String email,
            String name,
            String description,
            Map<String, Object> metadata
    ) {}

    record UpdateCustomerRequest(
            String email,
            String name,
            String description,
            Map<String, Object> metadata
    ) {}

    record DeleteResponse(
            String id,
            String object,
            boolean deleted
    ) {}
}
