package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Webhook endpoint registration")
public record WebhookEndpointResponse(
        @Schema(description = "Endpoint ID")
        String id,

        @Schema(description = "Webhook delivery URL")
        String url,

        @Schema(description = "Description")
        String description,

        @Schema(description = "Signing secret (only shown at creation)")
        String secret,

        @Schema(description = "Subscribed event types")
        List<String> events,

        @Schema(description = "Whether the endpoint is active")
        boolean enabled,

        @Schema(description = "When the endpoint was registered")
        Instant created_at
) {
}
