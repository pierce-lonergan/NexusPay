package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * TEST-4a (F2): the exact delivered bytes of ONE webhook delivery the caller OWNS
 * ({@code GET /v1/webhook-deliveries/{id}/body}).
 *
 * <p>Carries {@code canonical_body} — the caller's OWN delivered envelope bytes — so an integrator can
 * debug signature verification against the precise payload that was signed. Deliberately carries NO secret
 * (the signing secret is not even a column on the delivery entity). Returned ONLY to the owning tenant via
 * {@code findByIdAndTenantId} (a foreign/missing id → 404, no existence oracle). snake_case component names
 * per L-072.</p>
 *
 * @since TEST-4a
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "The exact delivered body of a webhook delivery the caller owns")
public record WebhookDeliveryBodyResponse(
        @Schema(description = "Delivery id", example = "whd_abc123")
        String id,

        @Schema(description = "Target webhook endpoint id", example = "we_abc123")
        String endpoint_id,

        @Schema(description = "Stable logical event id", example = "evt_abc123")
        String event_id,

        @Schema(description = "Dotted canonical event type", example = "payment.succeeded")
        String event_type,

        @Schema(description = "The EXACT canonical envelope bytes that were delivered + signed")
        String canonical_body
) {
}
