package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.gateway.adapter.in.rest.dto.CreateWebhookEndpointRequest;
import io.nexuspay.gateway.adapter.in.rest.dto.WebhookDeliveryBodyResponse;
import io.nexuspay.gateway.adapter.in.rest.dto.WebhookDeliveryResponse;
import io.nexuspay.gateway.adapter.in.rest.dto.WebhookDeliverySignatureResponse;
import io.nexuspay.gateway.adapter.in.rest.dto.WebhookEndpointResponse;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookDeliveryRepository;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookDeliveryEntity;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
import io.nexuspay.gateway.adapter.out.webhook.WebhookSignature;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@RestController
@Tag(name = "Webhook Endpoints", description = "Manage merchant webhook URLs and inspect deliveries")
public class WebhookEndpointController {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final JpaWebhookEndpointRepository webhookEndpointRepository;
    private final JpaWebhookDeliveryRepository webhookDeliveryRepository;

    public WebhookEndpointController(JpaWebhookEndpointRepository webhookEndpointRepository,
                                     JpaWebhookDeliveryRepository webhookDeliveryRepository) {
        this.webhookEndpointRepository = webhookEndpointRepository;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
    }

    @PostMapping("/v1/webhook-endpoints")
    @PreAuthorize("hasRole('admin') and @scopeAuth.has('webhooks:write')")
    @Operation(summary = "Register a webhook endpoint")
    public ResponseEntity<WebhookEndpointResponse> create(
            @Valid @RequestBody CreateWebhookEndpointRequest request,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        var secret = generateWebhookSecret();
        var entity = new WebhookEndpointEntity(
                PrefixedId.webhookEndpoint(),
                request.url(), request.description(), secret,
                request.events(), principal.tenantId()
        );
        webhookEndpointRepository.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseMapper.toWebhookEndpointResponse(entity, true));
    }

    @GetMapping("/v1/webhook-endpoints")
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('webhooks:read')")
    @Operation(summary = "List registered webhook endpoints")
    public ResponseEntity<List<WebhookEndpointResponse>> list(
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        var endpoints = webhookEndpointRepository
                .findAllByTenantIdAndEnabledTrue(principal.tenantId()).stream()
                .map(e -> ResponseMapper.toWebhookEndpointResponse(e, false))
                .toList();
        return ResponseEntity.ok(endpoints);
    }

    @DeleteMapping("/v1/webhook-endpoints/{id}")
    @PreAuthorize("hasRole('admin') and @scopeAuth.has('webhooks:write')")
    @Operation(summary = "Delete a webhook endpoint")
    public ResponseEntity<Void> delete(@PathVariable String id,
                                       @AuthenticationPrincipal NexusPayPrincipal principal) {
        // SEC-19: scope the lookup to the caller's tenant so a foreign id cannot disable another
        // tenant's endpoint. A cross-tenant (or absent) id silently no-ops to 204 — consistent with
        // the existing 204-on-miss behaviour and giving no existence oracle.
        webhookEndpointRepository.findByIdAndTenantId(id, principal.tenantId()).ifPresent(entity -> {
            entity.setEnabled(false);
            webhookEndpointRepository.save(entity);
        });
        return ResponseEntity.noContent().build();
    }

    /**
     * INT-4: rotate an endpoint's signing secret. Reuses the EXACT create-time generator (SecureRandom,
     * {@code whsec_} prefix). The new secret is returned ONCE ({@code includeSecret=true}); list/get never
     * return it. The next webhook attempt signs with the new secret automatically because the sender reads
     * {@code getSecret()} live per attempt. Tenant-scoped (foreign/absent id -> 404, no existence oracle).
     */
    @PostMapping("/v1/webhook-endpoints/{id}/rotate-secret")
    @PreAuthorize("hasRole('admin') and @scopeAuth.has('webhooks:write')")
    @Operation(summary = "Rotate a webhook endpoint's signing secret")
    public ResponseEntity<WebhookEndpointResponse> rotateSecret(
            @PathVariable String id,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        var endpoint = webhookEndpointRepository.findByIdAndTenantId(id, principal.tenantId())
                .orElseThrow(() -> ResourceNotFoundException.of("WebhookEndpoint", id));
        endpoint.rotateSecret(generateWebhookSecret());
        webhookEndpointRepository.save(endpoint);
        return ResponseEntity.ok(ResponseMapper.toWebhookEndpointResponse(endpoint, true));
    }

    /**
     * INT-4: list delivery attempts for the caller's tenant, optionally filtered by endpoint and/or event.
     * Tenant-scoped (never another tenant's rows). The DTO carries no secret/body — leak-impossible.
     */
    @GetMapping("/v1/webhook-deliveries")
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('webhooks:read')")
    @Operation(summary = "List outbound webhook delivery attempts")
    public ResponseEntity<Page<WebhookDeliveryResponse>> listDeliveries(
            @RequestParam(required = false) String endpointId,
            @RequestParam(required = false) String eventId,
            @PageableDefault(size = 50) Pageable pageable,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        String tenant = principal.tenantId();
        Page<WebhookDeliveryEntity> page;
        if (endpointId != null && eventId != null) {
            page = webhookDeliveryRepository.findByTenantIdAndEndpointIdAndEventId(tenant, endpointId, eventId, pageable);
        } else if (endpointId != null) {
            page = webhookDeliveryRepository.findByTenantIdAndEndpointId(tenant, endpointId, pageable);
        } else if (eventId != null) {
            page = webhookDeliveryRepository.findByTenantIdAndEventId(tenant, eventId, pageable);
        } else {
            page = webhookDeliveryRepository.findByTenantId(tenant, pageable);
        }
        return ResponseEntity.ok(page.map(ResponseMapper::toWebhookDeliveryResponse));
    }

    /**
     * INT-4: replay a delivery — the ONLY way a DELIVERED/DEAD row is re-sent (invariant #6). Re-arms the
     * existing row (status -> FAILED, next_attempt_at -> now) so the leader-locked retrier picks it up once;
     * it is an UPDATE, never a new INSERT, so the (endpoint,event) uniqueness is untouched (this is how a
     * replay differs from a duplicate). Tenant-scoped (foreign/absent id -> 404, no existence oracle).
     */
    @PostMapping("/v1/webhook-deliveries/{id}/replay")
    @PreAuthorize("hasRole('admin') and @scopeAuth.has('webhooks:write')")
    @Operation(summary = "Replay a webhook delivery")
    public ResponseEntity<WebhookDeliveryResponse> replay(
            @PathVariable String id,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        var delivery = webhookDeliveryRepository.findByIdAndTenantId(id, principal.tenantId())
                .orElseThrow(() -> ResourceNotFoundException.of("WebhookDelivery", id));
        delivery.requeueForReplay(Instant.now());
        return ResponseEntity.accepted()
                .body(ResponseMapper.toWebhookDeliveryResponse(webhookDeliveryRepository.save(delivery)));
    }

    /** Rotated-secret caveat surfaced on the F2 signature response (and documented in javadoc/OpenAPI/SDK). */
    private static final String ROTATED_SECRET_CAVEAT =
            "Signature is recomputed with the endpoint's CURRENT secret (as the sender signs per attempt). "
            + "If the secret was rotated after this delivery, this value differs from the originally-"
            + "delivered X-NexusPay-Signature header — it is not proof the original delivery was mis-signed.";

    /**
     * TEST-4a (F2): inspect the EXACT delivered body of ONE delivery the caller OWNS, so an integrator can
     * debug signature verification against the precise bytes that were signed. Tenant-scoped via
     * {@code findByIdAndTenantId} — a foreign/missing id is indistinguishable (404, no existence oracle),
     * mirroring the {@code /replay} resolution. The {@code canonical_body} is the caller's OWN delivered
     * bytes (it contains only the merchant's own data.metadata); no secret is on this entity, so the body
     * route cannot leak a key. Same {@code webhooks:read} scope as the delivery list.
     */
    @GetMapping("/v1/webhook-deliveries/{id}/body")
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('webhooks:read')")
    @Operation(summary = "Get the exact delivered body of a webhook delivery the caller owns")
    public ResponseEntity<WebhookDeliveryBodyResponse> getDeliveryBody(
            @PathVariable String id,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        var delivery = webhookDeliveryRepository.findByIdAndTenantId(id, principal.tenantId())
                .orElseThrow(() -> ResourceNotFoundException.of("WebhookDelivery", id));
        return ResponseEntity.ok(new WebhookDeliveryBodyResponse(
                delivery.getId(), delivery.getEndpointId(), delivery.getEventId(),
                delivery.getEventType(), delivery.getCanonicalBody()));
    }

    /**
     * TEST-4a (F2): recompute the HMAC-SHA256 signature for ONE delivery the caller OWNS, so an integrator
     * can compare it against their own verifier. The signature is recomputed over the stored
     * {@code canonical_body} using the OWNING endpoint's CURRENT secret (read tenant-scoped from
     * {@code webhook_endpoints}) via the SINGLE-SOURCED {@link WebhookSignature#sign} — the same routine the
     * sender uses, so the algorithm is never forked. The SECRET is NEVER returned (only the algorithm + hex
     * signature + endpoint id).
     *
     * <p>Resolution is tenant-scoped end to end: a foreign/missing delivery → 404 (no oracle); and if the
     * owning endpoint is no longer resolvable for the tenant (deleted/foreign), also 404 (no secret to sign
     * with — never fabricate a signature). ROTATED-SECRET CAVEAT: see {@link #ROTATED_SECRET_CAVEAT}.</p>
     */
    @GetMapping("/v1/webhook-deliveries/{id}/signature")
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('webhooks:read')")
    @Operation(summary = "Recompute a webhook delivery's signature (never returns the secret)")
    public ResponseEntity<WebhookDeliverySignatureResponse> getDeliverySignature(
            @PathVariable String id,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        String tenant = principal.tenantId();
        var delivery = webhookDeliveryRepository.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> ResourceNotFoundException.of("WebhookDelivery", id));
        // Load the OWNING endpoint tenant-scoped to read its CURRENT secret. If it is no longer resolvable
        // for this tenant, treat as 404 (no oracle) rather than fabricating a signature.
        var endpoint = webhookEndpointRepository.findByIdAndTenantId(delivery.getEndpointId(), tenant)
                .orElseThrow(() -> ResourceNotFoundException.of("WebhookDelivery", id));
        // Recompute via the single-sourced helper using the CURRENT secret (discarded immediately after).
        String signature = WebhookSignature.sign(delivery.getCanonicalBody(), endpoint.getSecret());
        return ResponseEntity.ok(new WebhookDeliverySignatureResponse(
                delivery.getId(), endpoint.getId(), WebhookSignature.ALGORITHM,
                signature, ROTATED_SECRET_CAVEAT));
    }

    private String generateWebhookSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
