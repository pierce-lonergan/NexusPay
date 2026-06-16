package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.gateway.adapter.in.rest.dto.CreateWebhookEndpointRequest;
import io.nexuspay.gateway.adapter.in.rest.dto.WebhookDeliveryResponse;
import io.nexuspay.gateway.adapter.in.rest.dto.WebhookEndpointResponse;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookDeliveryRepository;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookDeliveryEntity;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
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
    @PreAuthorize("hasRole('admin')")
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
    @PreAuthorize("hasAnyRole('admin', 'operator')")
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
    @PreAuthorize("hasRole('admin')")
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
    @PreAuthorize("hasRole('admin')")
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
    @PreAuthorize("hasAnyRole('admin', 'operator')")
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
    @PreAuthorize("hasRole('admin')")
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

    private String generateWebhookSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
