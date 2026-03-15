package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.CreateApiKeyRequest;
import io.nexuspay.gateway.adapter.in.rest.dto.CreateApiKeyResponse;
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

import java.time.Instant;

@RestController
@RequestMapping("/v1/api-keys")
@Tag(name = "API Keys", description = "Manage API keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Create a new API key. The full key is shown once.")
    public ResponseEntity<CreateApiKeyResponse> create(
            @Valid @RequestBody CreateApiKeyRequest request,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        var result = apiKeyService.createApiKey(
                request.name(), request.role(), principal.tenantId(), request.live());
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreateApiKeyResponse(
                result.id(), result.fullKey(), result.keyPrefix(),
                request.name(), request.role(), request.live(), Instant.now()
        ));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Revoke an API key")
    public ResponseEntity<Void> revoke(@PathVariable String id) {
        apiKeyService.revokeApiKey(id);
        return ResponseEntity.noContent().build();
    }
}
