package io.nexuspay.gateway.adapter.in.rest.dto;

import io.nexuspay.gateway.adapter.in.rest.validation.SafeWebhookUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Schema(description = "Register a webhook endpoint")
public record CreateWebhookEndpointRequest(
        // SEC-14: @SafeWebhookUrl is the registration-time SSRF gate — requires https and rejects
        // hosts resolving to internal/loopback/link-local/metadata/ULA addresses. A violation surfaces
        // as MethodArgumentNotValidException -> 400 via GlobalExceptionHandler.
        @NotBlank @SafeWebhookUrl
        @Schema(description = "HTTPS URL to receive webhook events", example = "https://merchant.com/webhooks")
        String url,

        @Schema(description = "Optional description")
        String description,

        @NotEmpty @Schema(description = "Event types to subscribe to", example = "[\"payment.captured\", \"refund.completed\"]")
        List<String> events
) {
}
