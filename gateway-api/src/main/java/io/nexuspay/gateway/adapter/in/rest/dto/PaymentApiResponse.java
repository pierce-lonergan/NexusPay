package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * Gateway API payment response DTO.
 * Wraps payment-orchestration's PaymentResponse into the public API shape.
 *
 * <p>Critique 5.1: the {@code POST /v1/payments} create response carries ONLY the payment {@code id}
 * (plus status/amount/mode etc.) and has NO {@code client_secret} field. A {@code client_secret} for a
 * browser/SDK client is issued by {@code POST /v1/payment-sessions} ({@code PaymentSessionResponse}),
 * not by this server-to-server create. Do not look for a client secret on this object.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Payment object. The create response (POST /v1/payments) returns only the payment "
        + "id (no client_secret); a browser client_secret comes from POST /v1/payment-sessions "
        + "(PaymentSessionResponse).")
public record PaymentApiResponse(
        @Schema(description = "Payment ID from the gateway")
        String id,

        @Schema(description = "Payment status")
        String status,

        @Schema(description = "Amount in minor units")
        long amount,

        @Schema(description = "Three-letter ISO 4217 currency code")
        String currency,

        @Schema(description = "Capture method: automatic or manual")
        String capture_method,

        @Schema(description = "Customer identifier")
        String customer_id,

        @Schema(description = "Connector that processed the payment")
        String connector,

        @Schema(description = "Error code if payment failed")
        String error_code,

        @Schema(description = "Error message if payment failed")
        String error_message,

        @Schema(description = "When the payment was created")
        Instant created_at,

        @Schema(description = "Arbitrary key-value metadata")
        Map<String, Object> metadata,

        @Schema(description = "Key mode that produced this payment: \"test\" or \"live\". INT-3: "
                + "SERVER-DERIVED from the authenticated key's is_live — never from the request body.")
        String mode,

        @Schema(description = "true when mode == live; mirrors the webhook envelope's livemode. "
                + "Nullable: dropped (NON_NULL) on the no-mode overload alongside mode.")
        Boolean livemode,

        @Schema(description = "TEST-6 (A3): the 3DS/SCA next action to perform. Present ONLY for a "
                + "requires_action payment (dropped by NON_NULL otherwise). An integrator drives their "
                + "SCA/redirect handling from this; in TEST mode the url is a harmless stub.")
        NextAction next_action
) {
    /**
     * TEST-6 (A3): the typed 3DS/redirect next action surfaced on a requires_action payment. The component
     * names ({@code type}, {@code url}) ARE the snake-shaped wire keys (no transform; both are single tokens),
     * matching the DTO's component-name-as-wire-name style (L-072). NON_NULL drops the enclosing
     * {@code next_action} for non-action responses.
     *
     * <p>DELIBERATE BLUEPRINT DEVIATION: this is the FLAT {@code {type, url}} shape (NOT the blueprint's
     * nested {@code {type, redirect_to_url:{url}}}), chosen to expose exactly ONE {@code next_action} shape
     * across the confirm path (INT-6 {@code ConfirmResponse.NextAction}, which already locked {@code {type,
     * url}}) and this payment path. Full rationale + trade-off on {@code PaymentResponse.NextAction}.</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NextAction(
            @Schema(description = "The next-action type, e.g. \"redirect_to_url\".")
            String type,
            @Schema(description = "The redirect URL the client should send the cardholder to (3DS/SCA). "
                    + "In TEST mode this is a harmless stub under test.nexuspay.local.")
            String url
    ) {
    }
}
