package io.nexuspay.payment.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.nexuspay.common.tenant.CallerMode;
import io.nexuspay.common.tenant.CallerTenant;
import io.nexuspay.payment.application.service.mandate.MandateService;
import io.nexuspay.payment.domain.mandate.Mandate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for the Mandate / consent resource (TEST-3d) — the recorded off-session consent of the
 * saved-credential cluster. Mirrors {@code PaymentMethodController}.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code POST /v1/mandates}          — create from a {@code pm_} (201, status ACTIVE, {@code customers:write})</li>
 *   <li>{@code GET  /v1/mandates/{id}}     — retrieve ({@code customers:read}, 404 no-oracle)</li>
 *   <li>{@code GET  /v1/mandates}          — list tenant-scoped ({@code customers:read})</li>
 *   <li>{@code POST /v1/mandates/{id}/revoke} — deactivate -> INACTIVE ({@code customers:write})</li>
 * </ul>
 *
 * <h3>Scopes</h3>
 * <p>A mandate is part of the customer's saved-credential consent cluster, so this resource REUSES the
 * existing {@code customers:read}/{@code customers:write} scopes — no new ApiScope vocabulary (avoids the
 * exact-set vocabulary guard). Each guard AND-composes the role check with a fail-closed
 * {@code @scopeAuth.has} scope check.</p>
 *
 * <h3>Tenant safety (SEC-26)</h3>
 * <p>The tenant is ALWAYS derived from {@link CallerTenant#require()}; {@code livemode}/{@code isTest} from
 * {@link CallerMode}. Neither is ever read from the body or a header. The response body NEVER exposes the
 * tenant.</p>
 *
 * @since TEST-3d
 */
@RestController
@RequestMapping("/v1/mandates")
public class MandateController {

    private final MandateService mandateService;

    public MandateController(MandateService mandateService) {
        this.mandateService = mandateService;
    }

    /**
     * Records a mandate from a tenant-owned {@code pm_}. {@code livemode} is server-derived from the caller
     * key mode and must equal the {@code pm_}'s livemode (400 on mismatch). The customer is derived from the
     * resolved {@code pm_}'s owner (never client-supplied). A foreign/missing {@code pm_} -> 404 no-oracle.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('customers:write')")
    public ResponseEntity<MandateResponse> createMandate(
            @RequestBody(required = false) CreateMandateRequest request) {

        CreateMandateRequest body = request != null
                ? request
                : new CreateMandateRequest(null, null, null, null);

        // SEC-26: tenant from the authenticated principal, never a client header/body.
        String tenantId = CallerTenant.require();
        // livemode/isTest server-derived from the caller key mode; NOT from the request.
        boolean livemode = CallerMode.isLive();
        boolean isTest = CallerMode.isTest();

        Mandate mandate = mandateService.create(
                tenantId, livemode, isTest, body.paymentMethodId(), body.type(), body.scenario(),
                body.metadata());

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(mandate));
    }

    /**
     * Retrieves one mandate by id. Tenant-scoped — a foreign-tenant/absent id 404s with no existence oracle
     * (same {@code .orElse(notFound())} idiom as the pm/customer controllers). A revoked (INACTIVE) mandate
     * IS still retrievable (no soft-delete filter).
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer') and @scopeAuth.has('customers:read')")
    public ResponseEntity<MandateResponse> getMandate(@PathVariable String id) {
        return mandateService.findById(id, CallerTenant.require())
                .map(m -> ResponseEntity.ok(toResponse(m)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Lists the caller tenant's mandates, newest first, paginated by {@code limit}/{@code offset}.
     * Enumerates only the caller tenant's mandates.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer') and @scopeAuth.has('customers:read')")
    public ResponseEntity<List<MandateResponse>> listMandates(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<Mandate> mandates = mandateService.listByTenant(CallerTenant.require(), limit, offset);
        return ResponseEntity.ok(mandates.stream().map(this::toResponse).toList());
    }

    /**
     * REVOKE a mandate the caller owns (deactivate -> status INACTIVE + revoked_at). A foreign/absent id
     * 404s via {@code TenantOwnership} (no oracle). The mandate stays retrievable afterwards (INACTIVE).
     * POST-to-id idiom (mirrors customer {@code POST /{id}} + dispute {@code /{id}/submit}).
     */
    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('customers:write')")
    public ResponseEntity<MandateResponse> revokeMandate(@PathVariable String id) {
        Mandate mandate = mandateService.revoke(id, CallerTenant.require());
        return ResponseEntity.ok(toResponse(mandate));
    }

    // ---- Response / Request DTOs ----

    private MandateResponse toResponse(Mandate m) {
        // tenant is NEVER exposed in the body. `created` is epoch seconds.
        return new MandateResponse(
                m.getId(),
                "mandate",
                m.isLivemode(),
                m.getStatus(),
                m.getType(),
                m.getCustomerId(),
                m.getPaymentMethodId(),
                m.getScenario(),
                m.getCreatedAt() != null ? m.getCreatedAt().getEpochSecond() : null);
    }

    record MandateResponse(
            String id,
            String object,
            boolean livemode,
            String status,
            String type,
            String customer,
            @JsonProperty("payment_method") String paymentMethod,
            String scenario,
            Long created
    ) {}

    /**
     * Create body. {@code paymentMethod} ({@code pm_}) is the saved method the consent authorizes; the
     * mandate's customer is DERIVED from it server-side (no customer field here).
     *
     * <p>L-072: the multi-word {@code payment_method} component carries {@code @JsonProperty} so Jackson
     * binds the DOCUMENTED snake_case wire key — the SDK, OpenAPI, and the catalog curl all send
     * {@code payment_method}. There is NO global Jackson snake_case strategy, so without this the key would
     * deserialize to null and every create would 400.</p>
     */
    record CreateMandateRequest(
            @JsonProperty("payment_method") String paymentMethodId,
            String type,
            String scenario,
            Map<String, Object> metadata
    ) {}
}
