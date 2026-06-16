package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * INT-6: result of confirming a checkout session via the SDK {@code POST /v1/checkout/confirm}.
 *
 * <p>This is the contract {@code @nexuspay/js}'s {@code confirm()} consumes directly — the SDK's
 * {@code HttpClient} does {@code response.json() as T} with NO snake&harr;camel transform, so the wire
 * keys must literally match the TS {@code ConfirmResult}. The fields the client already reads
 * ({@code status}, {@code nextAction}, {@code error}) are camelCase; INT-6 adds {@code paymentId},
 * {@code mode}, and {@code livemode}. Each {@link JsonProperty} pins the wire name defensively so a
 * global snake_case {@code PropertyNamingStrategy} (if ever configured) cannot rewrite them.
 *
 * <p>{@code @JsonInclude(NON_NULL)} drops {@code nextAction}/{@code error} on the happy path, keeping the
 * success body minimal ({@code {status, paymentId, mode, livemode}}).
 *
 * @since INT-6
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Result of confirming a checkout session (SDK confirm)")
public record ConfirmResponse(
        @Schema(description = "Confirm outcome: succeeded | processing | requires_action | failed")
        @JsonProperty("status") String status,

        @Schema(description = "Gateway payment id (pay_test_* in test mode, opaque connector id in live)")
        @JsonProperty("paymentId") String paymentId,

        @Schema(description = "Key mode that produced this payment: \"test\" or \"live\". INT-3: "
                + "SERVER-DERIVED from the authenticated key's is_live — never from the request body.")
        @JsonProperty("mode") String mode,

        @Schema(description = "true when mode == \"live\" (mirrors the webhook envelope's livemode flag)")
        @JsonProperty("livemode") boolean livemode,

        @Schema(description = "Client follow-up action; present (non-null) only when status=requires_action")
        @JsonProperty("nextAction") NextAction nextAction,

        @Schema(description = "Failure detail; present (non-null) only when status=failed")
        @JsonProperty("error") ConfirmError error
) {

    /**
     * Client follow-up action (3DS / redirect). Mirrors the TS
     * {@code nextAction?: { type: 'redirect' | 'three_d_secure'; url?: string }}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Follow-up action the client must perform (e.g. 3DS redirect)")
    public record NextAction(
            @Schema(description = "Action type: redirect | three_d_secure")
            @JsonProperty("type") String type,

            @Schema(description = "Redirect / challenge URL")
            @JsonProperty("url") String url
    ) {
    }

    /**
     * Failure detail. Shape mirrors the SDK's {@code NexusPayError} ({@code type}/{@code code}/
     * {@code message}) so {@code result.error} drops straight into the client's error handling.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Failure detail (mirrors NexusPayError)")
    public record ConfirmError(
            @Schema(description = "Error category, e.g. payment_error")
            @JsonProperty("type") String type,

            @Schema(description = "Machine-readable error code, e.g. card_declined")
            @JsonProperty("code") String code,

            @Schema(description = "Safe, human-readable message")
            @JsonProperty("message") String message
    ) {
    }
}
