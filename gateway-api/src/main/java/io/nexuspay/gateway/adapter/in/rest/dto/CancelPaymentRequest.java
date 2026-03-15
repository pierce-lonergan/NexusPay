package io.nexuspay.gateway.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Cancel/void a payment authorization")
public record CancelPaymentRequest(
        @Schema(description = "Reason for cancellation")
        String cancellation_reason
) {
}
