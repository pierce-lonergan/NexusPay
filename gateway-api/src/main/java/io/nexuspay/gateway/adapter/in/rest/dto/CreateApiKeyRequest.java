package io.nexuspay.gateway.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

@Schema(description = "Create a new API key")
public record CreateApiKeyRequest(
        @NotBlank @Schema(description = "Human-readable name for the key", example = "Production Key")
        String name,

        @NotBlank @Pattern(regexp = "admin|operator|viewer")
        @Schema(description = "Role assigned to this key", example = "operator")
        String role,

        @Schema(description = "Whether this is a live (production) key", example = "false")
        boolean live,

        // DX-5c: optional absolute expiry (ISO-8601 instant). null = never expires (back-compat).
        // @Future rejects an at-or-before-now value early; the service re-validates fail-closed too.
        @Future
        @Schema(description = "Optional ISO-8601 expiry instant. Omit for a key that never expires.",
                example = "2027-01-01T00:00:00Z")
        Instant expiresAt,

        // DX-5c-ii: OPTIONAL scopes restricting this key ON TOP OF its role. Omit (or empty) for an
        // UNRESTRICTED (role-based) key — back-compat. Each value must be a member of the ApiScope
        // vocabulary; the service validates fail-closed (400) on any unknown scope. Scopes NARROW the
        // role, never widen it.
        @Schema(description = "Optional scopes (resource:action) that NARROW this key. Omit for an "
                + "unrestricted (role-based) key.",
                example = "[\"payments:read\",\"payments:write\"]")
        List<String> scopes
) {
}
