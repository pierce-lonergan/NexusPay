package io.nexuspay.gateway.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

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
        Instant created_at,

        @Schema(description = "When the key expires, or null if it never expires")
        Instant expires_at,

        // DX-5c-ii: the key's scopes (resource:action). Empty means UNRESTRICTED (role-based).
        @Schema(description = "Scopes this key is restricted to. Empty means unrestricted (role-based).")
        List<String> scopes
) {
}
