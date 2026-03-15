package io.nexuspay.gateway.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "Create a new API key")
public record CreateApiKeyRequest(
        @NotBlank @Schema(description = "Human-readable name for the key", example = "Production Key")
        String name,

        @NotBlank @Pattern(regexp = "admin|operator|viewer")
        @Schema(description = "Role assigned to this key", example = "operator")
        String role,

        @Schema(description = "Whether this is a live (production) key", example = "false")
        boolean live
) {
}
