package io.nexuspay.gateway.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Schema(description = "Register a webhook endpoint")
public record CreateWebhookEndpointRequest(
        @NotBlank @Schema(description = "HTTPS URL to receive webhook events", example = "https://merchant.com/webhooks")
        String url,

        @Schema(description = "Optional description")
        String description,

        @NotEmpty @Schema(description = "Event types to subscribe to", example = "[\"payment.captured\", \"refund.completed\"]")
        List<String> events
) {
}
