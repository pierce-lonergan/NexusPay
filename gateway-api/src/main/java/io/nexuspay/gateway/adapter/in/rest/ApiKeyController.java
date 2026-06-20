package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.CreateApiKeyRequest;
import io.nexuspay.gateway.adapter.in.rest.dto.CreateApiKeyResponse;
import io.nexuspay.gateway.adapter.in.rest.dto.RotateApiKeyRequest;
import io.nexuspay.iam.application.ApiKeyService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/v1/api-keys")
@Tag(name = "API Keys", description = "Manage API keys")
public class ApiKeyController {

    // DX-5c: rotation overlap policy — server default and a hard cap. An unbounded client value is
    // CLAMPED to the cap (never trusted): rotation must not be a way to extend a key's life arbitrarily.
    private static final long DEFAULT_OVERLAP_SECONDS = 86_400L;      // 24h
    private static final long MAX_OVERLAP_SECONDS = 7L * 86_400L;     // 7 days

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    @PreAuthorize("hasRole('admin') and @scopeAuth.has('keys:write')")
    @Operation(summary = "Create a new API key. The full key is shown once.")
    public ResponseEntity<CreateApiKeyResponse> create(
            @Valid @RequestBody CreateApiKeyRequest request,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        // DX-5c: pass the optional expiry through. @Future on the DTO rejects an at-or-before-now value
        // early; the service re-validates fail-closed (defence in depth).
        // DX-5c-ii: pass the optional scopes through. The service validates fail-closed against the
        // ApiScope vocabulary (400 on an unknown scope) and persists the canonical csv. null/empty =
        // unrestricted (role-based) — back-compat.
        Set<String> requestedScopes = request.scopes() == null ? null : new HashSet<>(request.scopes());
        var result = apiKeyService.createApiKey(
                request.name(), request.role(), principal.tenantId(), request.live(), request.expiresAt(),
                requestedScopes);
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreateApiKeyResponse(
                result.id(), result.fullKey(), result.keyPrefix(),
                result.name(), result.role(), result.live(), Instant.now(), result.expiresAt(),
                toScopeList(result.scopes())
        ));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('admin') and @scopeAuth.has('keys:write')")
    @Operation(summary = "Revoke an API key")
    public ResponseEntity<Void> revoke(@PathVariable String id,
                                       @AuthenticationPrincipal NexusPayPrincipal principal) {
        // DX-5c IDOR fix: tenant-scoped revoke — an admin can only revoke keys in their OWN tenant. An
        // other-tenant key id resolves to the uniform invalid_api_key (no cross-tenant existence oracle).
        apiKeyService.revokeApiKey(id, principal.tenantId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/rotate")
    @PreAuthorize("hasRole('admin') and @scopeAuth.has('keys:write')")
    @Operation(summary = "Rotate an API key with an overlap window. The new full key is shown once.")
    public ResponseEntity<CreateApiKeyResponse> rotate(
            @PathVariable String id,
            @Valid @RequestBody(required = false) RotateApiKeyRequest request,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        // Resolve overlap: client value or server default, CLAMPED to [0, cap]. Never trust an unbounded
        // value (rotation is not a lifetime-escalation lever).
        long requested = (request != null && request.overlapSeconds() != null)
                ? request.overlapSeconds()
                : DEFAULT_OVERLAP_SECONDS;
        long clamped = Math.max(0L, Math.min(requested, MAX_OVERLAP_SECONDS));
        Duration overlap = Duration.ofSeconds(clamped);

        var result = apiKeyService.rotateApiKey(id, principal.tenantId(), overlap);
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreateApiKeyResponse(
                result.id(), result.fullKey(), result.keyPrefix(),
                result.name(), result.role(), result.live(), Instant.now(), result.expiresAt(),
                toScopeList(result.scopes())  // DX-5c-ii: rotation INHERITS the rotated key's scopes
        ));
    }

    /** DX-5c-ii: render the result's scope set as a stable List for the JSON response (never null). */
    private static List<String> toScopeList(Set<String> scopes) {
        return scopes == null ? List.of() : new ArrayList<>(scopes);
    }
}
