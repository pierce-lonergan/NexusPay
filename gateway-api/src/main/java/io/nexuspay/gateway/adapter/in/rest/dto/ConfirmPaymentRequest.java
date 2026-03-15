package io.nexuspay.gateway.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Confirm a payment intent")
public record ConfirmPaymentRequest(
        @Schema(description = "Payment method type", example = "card")
        String payment_method_type,

        @Schema(description = "Payment method data")
        String payment_method_data,

        @Schema(description = "URL to redirect after authentication")
        String return_url
) {
}
