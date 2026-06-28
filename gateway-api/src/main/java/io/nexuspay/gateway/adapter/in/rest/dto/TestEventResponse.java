package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * TEST-4a (D1): response for {@code POST /v1/test/events}.
 *
 * <p>Echoes the synthesized event the trigger wrote to the outbox so an integrator can correlate the
 * webhook they receive: the stable event {@code id}, the dotted canonical {@code type}, {@code livemode}
 * (always {@code false} — a test trigger can never synthesize a live event), and the {@code object} that
 * was delivered as {@code data.object}. snake_case component names per L-072 (the record components are
 * already snake_case-safe single words).</p>
 *
 * @since TEST-4a
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A synthesized test webhook event (POST /v1/test/events)")
public record TestEventResponse(
        @Schema(description = "Stable event id (matches the delivered webhook's `id`)", example = "evt_abc123")
        String id,

        @Schema(description = "Dotted canonical event type", example = "payment.succeeded")
        String type,

        @Schema(description = "Always false — a test trigger can never synthesize a live event")
        boolean livemode,

        @Schema(description = "The synthesized data.object delivered in the webhook body")
        Map<String, Object> object
) {
}
