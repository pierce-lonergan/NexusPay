package io.nexuspay.gateway.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Newly created API key. The full key is shown ONCE and cannot be retrieved again.")
public record CreateApiKeyResponse(
        @Schema(description = "Key ID")
        String id,

        @Schema(description = "Full API key — store this securely, it will not be shown again")
        String key,

        @Schema(description = "Key prefix for identification")
        String key_prefix,

        @Schema(description = "Human-readable name")
        String name,

        @Schema(description = "Role assigned to this key")
        String role,

        @Schema(description = "Whether this is a live key")
        boolean live,

        @Schema(description = "When the key was created")
        Instant created_at
) {
}
