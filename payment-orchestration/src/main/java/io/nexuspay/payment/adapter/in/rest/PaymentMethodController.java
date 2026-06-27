package io.nexuspay.payment.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.nexuspay.common.exception.InvalidRequestException;
import io.nexuspay.common.tenant.CallerMode;
import io.nexuspay.common.tenant.CallerTenant;
import io.nexuspay.payment.application.service.paymentmethod.PaymentMethodService;
import io.nexuspay.payment.domain.paymentmethod.PaymentMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * REST API for the saved Payment Method resource (TEST-3b) — the multi-use credential of the
 * saved-credential cluster. Mirrors {@code CustomerController}.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code POST   /v1/customers/{customerId}/payment_methods} — attach (201, {@code customers:write})</li>
 *   <li>{@code GET    /v1/customers/{customerId}/payment_methods} — list ({@code customers:read})</li>
 *   <li>{@code GET    /v1/payment_methods/{id}}                   — retrieve ({@code customers:read}, 404 no-oracle)</li>
 *   <li>{@code DELETE /v1/payment_methods/{id}}                   — detach=soft delete ({@code customers:write})</li>
 * </ul>
 *
 * <h3>Scopes</h3>
 * <p>Saved methods are part of the customer's saved credentials (Stripe groups them), so this resource
 * REUSES the existing {@code customers:read}/{@code customers:write} scopes — no new ApiScope vocabulary.
 * Each guard AND-composes the role check with a fail-closed {@code @scopeAuth.has} scope check.</p>
 *
 * <h3>PCI (SEC-BATCH-3) — the load-bearing constraint</h3>
 * <p>{@link AttachPaymentMethodRequest} declares NO {@code number}/{@code cvc}/{@code pan}/{@code card}
 * field, so an undeclared top-level raw-card key is DROPPED by Jackson's tolerant binding — it never binds
 * to a component, so it can never reach the service nor be persisted (the at-rest guarantee holds BY
 * CONSTRUCTION; a card number can only persist if it lands in a declared field or in {@code metadata}).
 * Those two reachable vectors are fail-closed with a 400: a card number in a DECLARED field
 * ({@code credential_ref}/{@code brand}/{@code funding}/{@code last4}) is rejected by
 * {@code PaymentMethodService.rejectPanShape}/last4 validation, and a PAN-like KEY in {@code metadata} by
 * {@link #rejectIfPanLike}. The response body NEVER exposes the tenant or the credential_ref.</p>
 *
 * @since TEST-3b
 */
@RestController
public class PaymentMethodController {

    /**
     * PCI guard: top-level request keys that look like raw card material. A body carrying any of these
     * (even though the typed record does not declare them, a client could send extra JSON keys) is a
     * 400 — a saved method must be created from a token, never a PAN.
     */
    private static final Set<String> PAN_LIKE_KEYS = Set.of(
            "number", "card_number", "cardnumber", "pan", "cvc", "cvv", "card");

    private final PaymentMethodService paymentMethodService;

    public PaymentMethodController(PaymentMethodService paymentMethodService) {
        this.paymentMethodService = paymentMethodService;
    }

    /**
     * Attaches a saved method to a tenant-owned customer. {@code livemode} is server-derived from the
     * caller key mode and must equal the customer's livemode (400 on mismatch). A fixture token
     * ({@code pm_card_*}) requires a test key (400 under a live key). A PAN-like body field -> 400, never
     * persisted.
     */
    @PostMapping("/v1/customers/{customerId}/payment_methods")
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('customers:write')")
    public ResponseEntity<PaymentMethodResponse> attachPaymentMethod(
            @PathVariable String customerId,
            @RequestBody(required = false) AttachPaymentMethodRequest request) {

        AttachPaymentMethodRequest body = request != null
                ? request
                : new AttachPaymentMethodRequest(null, null, null, null, null, null, null, null);

        // PCI: reject a raw-PAN-like metadata key BEFORE touching the service (belt-and-suspenders with the
        // MetadataSanitizer strip on the metadata map). An UNKNOWN top-level field (number/cvc/pan/card) is
        // dropped by Jackson (tolerant binding) so it never binds, never reaches the service, and is never
        // persisted; a PAN in a DECLARED field is rejected 400 by PaymentMethodService.rejectPanShape.
        rejectIfPanLike(body);

        // SEC-26: tenant from the authenticated principal, never a client header/body.
        String tenantId = CallerTenant.require();
        // livemode/isTest server-derived from the caller key mode; NOT from the request.
        boolean livemode = CallerMode.isLive();
        boolean isTest = CallerMode.isTest();

        PaymentMethod pm = paymentMethodService.attach(
                tenantId, customerId, livemode, isTest, body.type(), body.credentialRef(),
                body.brand(), body.last4(), body.expMonth(), body.expYear(), body.funding(),
                body.metadata());

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(pm));
    }

    /**
     * Lists a customer's live (non-detached) saved methods, newest first, paginated by {@code limit}/
     * {@code offset} (mirrors {@code CustomerController.listCustomers}). The customer is resolved
     * tenant-scoped first (404 no-oracle on a foreign customer, NOT an empty list).
     */
    @GetMapping("/v1/customers/{customerId}/payment_methods")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer') and @scopeAuth.has('customers:read')")
    public ResponseEntity<List<PaymentMethodResponse>> listPaymentMethods(
            @PathVariable String customerId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<PaymentMethod> methods =
                paymentMethodService.listByCustomer(customerId, CallerTenant.require(), limit, offset);
        return ResponseEntity.ok(methods.stream().map(this::toResponse).toList());
    }

    /**
     * Retrieves one saved method by id. Tenant-scoped — a foreign-tenant (or detached) id 404s with no
     * existence oracle (same {@code .orElse(notFound())} idiom as {@code CustomerController.getCustomer}).
     */
    @GetMapping("/v1/payment_methods/{id}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer') and @scopeAuth.has('customers:read')")
    public ResponseEntity<PaymentMethodResponse> getPaymentMethod(@PathVariable String id) {
        return paymentMethodService.findById(id, CallerTenant.require())
                .map(pm -> ResponseEntity.ok(toResponse(pm)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * DETACH a saved method the caller owns (soft delete — sets {@code deleted_at}). A foreign/absent/
     * already-detached id 404s via {@code TenantOwnership} (no oracle). Subsequent retrieve/list no longer
     * return the method.
     */
    @DeleteMapping("/v1/payment_methods/{id}")
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('customers:write')")
    public ResponseEntity<DeleteResponse> detachPaymentMethod(@PathVariable String id) {
        PaymentMethod pm = paymentMethodService.detach(id, CallerTenant.require());
        return ResponseEntity.ok(new DeleteResponse(pm.getId(), "payment_method", true));
    }

    // ---- PCI guard ----

    /**
     * PCI (SEC-BATCH-3): metadata-key guard. An UNDECLARED top-level field ({@code number}/{@code cvc}/
     * {@code pan}/{@code card}) is dropped by Jackson's tolerant binding — it never binds to a component, so
     * it can never reach the service or be persisted (the at-rest guarantee holds by construction). This
     * method rejects (400) a PAN-LIKE KEY (number/cvc/…) nested in the typed {@code metadata} map — the one
     * undeclared-key vector that WOULD otherwise persist (metadata is stored). A card number in a DECLARED
     * field is caught separately by {@code PaymentMethodService.rejectPanShape}/last4 validation. The metadata map is scanned by KEY only (a legitimate metadata VALUE may
     * legitimately be a long numeric string such as an order id; {@link io.nexuspay.common.metadata.MetadataSanitizer}
     * owns metadata value hygiene at the service layer).
     */
    private void rejectIfPanLike(AttachPaymentMethodRequest body) {
        Map<String, Object> metadata = body.metadata();
        if (metadata == null) {
            return;
        }
        for (String key : metadata.keySet()) {
            if (key != null && PAN_LIKE_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                throw new InvalidRequestException(
                        "Raw card data must never be sent to this endpoint; supply a tokenized "
                                + "credential_ref (a test fixture token or an opaque PSP reference)",
                        "raw_card_data_rejected");
            }
        }
    }

    // ---- Response / Request DTOs ----

    private PaymentMethodResponse toResponse(PaymentMethod pm) {
        // tenant and credential_ref are NEVER exposed in the body. `created` is epoch seconds.
        return new PaymentMethodResponse(
                pm.getId(),
                "payment_method",
                pm.isLivemode(),
                pm.getType(),
                pm.getCustomerId(),
                new PaymentMethodCard(pm.getBrand(), pm.getLast4(), pm.getExpMonth(), pm.getExpYear(),
                        pm.getFunding()),
                pm.getMetadata(),
                pm.getCreatedAt() != null ? pm.getCreatedAt().getEpochSecond() : null);
    }

    record PaymentMethodResponse(
            String id,
            String object,
            boolean livemode,
            String type,
            String customer,
            PaymentMethodCard card,
            Map<String, Object> metadata,
            Long created
    ) {}

    record PaymentMethodCard(
            String brand,
            String last4,
            Integer exp_month,
            Integer exp_year,
            String funding
    ) {}

    /**
     * Attach body. {@code credentialRef} is a fixture token ({@code pm_card_*}) in test mode or an opaque
     * pre-tokenized reference in live mode. Display fields are used only on the LIVE/opaque path (the
     * fixture is authoritative in test mode). There is deliberately NO {@code number}/{@code cvc}/
     * {@code pan}/{@code card} field — a saved method is created from a token, never a PAN.
     *
     * <p>Multi-word components carry {@code @JsonProperty} so Jackson binds the DOCUMENTED snake_case wire
     * keys ({@code credential_ref}/{@code exp_month}/{@code exp_year}) — the SDK, OpenAPI, and the catalog
     * curl all send snake_case, and the response side ({@link PaymentMethodCard}) already uses
     * {@code exp_month}/{@code exp_year}. Without this the keys would deserialize to null and EVERY real
     * integrator request would 400 {@code missing_credential}.</p>
     *
     * <p>PCI (SEC-BATCH-3): the record declares ONLY token + display fields, so an UNDECLARED top-level JSON
     * key (a smuggled {@code number}/{@code cvc}/{@code pan}/{@code card}) is dropped by Jackson under
     * Spring's default {@code FAIL_ON_UNKNOWN_PROPERTIES=false} and is therefore NEVER bound, NEVER reaches
     * the service, and NEVER persisted — the at-rest PCI guarantee holds by construction (a class-level
     * {@code @JsonIgnoreProperties(ignoreUnknown=false)} does NOT change this: it controls the ignore-SET,
     * not the fail decision, which the global feature owns — so it would not produce a 400 and is omitted).
     * The smuggle vectors that COULD otherwise persist a PAN are fail-closed with a 400: a card number in a
     * DECLARED field ({@code credential_ref}/{@code brand}/{@code funding}/{@code last4}) is rejected by
     * {@code PaymentMethodService.rejectPanShape}/last4 validation, and a PAN-like KEY in {@code metadata} by
     * {@link #rejectIfPanLike}.</p>
     */
    record AttachPaymentMethodRequest(
            String type,
            @JsonProperty("credential_ref") String credentialRef,
            String brand,
            String last4,
            @JsonProperty("exp_month") Integer expMonth,
            @JsonProperty("exp_year") Integer expYear,
            String funding,
            Map<String, Object> metadata
    ) {}

    record DeleteResponse(
            String id,
            String object,
            boolean deleted
    ) {}
}
