package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.gateway.adapter.in.rest.dto.CreateWebhookEndpointRequest;
import io.nexuspay.gateway.adapter.in.rest.dto.WebhookEndpointResponse;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

@RestController
@RequestMapping("/v1/webhook-endpoints")
@Tag(name = "Webhook Endpoints", description = "Manage merchant webhook URLs")
public class WebhookEndpointController {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final JpaWebhookEndpointRepository webhookEndpointRepository;

    public WebhookEndpointController(JpaWebhookEndpointRepository webhookEndpointRepository) {
        this.webhookEndpointRepository = webhookEndpointRepository;
    }

    @PostMapping
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

    @GetMapping
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

    @DeleteMapping("/{id}")
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

    private String generateWebhookSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
