package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * INT-4: list/replay projection of a {@code webhook_deliveries} row.
 *
 * <p>Metadata-light by design: it carries NO {@code canonical_body} (which could echo merchant correlation
 * metadata) and NO {@code secret} (the signing secret is not even a column on the delivery entity), so this
 * DTO cannot leak either by construction.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Outbound webhook delivery attempt record")
public record WebhookDeliveryResponse(
        @Schema(description = "Delivery ID")
        String id,

        @Schema(description = "Target webhook endpoint ID")
        String endpoint_id,

        @Schema(description = "Stable logical event ID")
        String event_id,

        @Schema(description = "Dotted canonical event type")
        String event_type,

        @Schema(description = "Delivery status: PENDING | DELIVERED | FAILED | DEAD")
        String status,

        @Schema(description = "Number of attempts already made")
        int attempt_count,

        @Schema(description = "Maximum attempts before the delivery is parked DEAD")
        int max_attempts,

        @Schema(description = "HTTP status code of the last attempt")
        Integer last_status_code,

        @Schema(description = "Bounded error summary of the last failed attempt")
        String last_error,

        @Schema(description = "When the next retry is due (null once DELIVERED/DEAD)")
        Instant next_attempt_at,

        @Schema(description = "When the delivery was first recorded")
        Instant created_at,

        @Schema(description = "When the delivery was last updated")
        Instant updated_at,

        @Schema(description = "When the delivery succeeded (null until DELIVERED)")
        Instant delivered_at
) {
}
