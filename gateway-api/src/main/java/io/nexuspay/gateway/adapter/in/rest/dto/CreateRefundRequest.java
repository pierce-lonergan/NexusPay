package io.nexuspay.gateway.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

@Schema(description = "Create a refund for a payment")
public record CreateRefundRequest(
        @Min(1) @Schema(description = "Refund amount in minor units", example = "2500")
        long amount,

        @Schema(description = "Three-letter ISO 4217 currency code", example = "USD")
        String currency,

        @Schema(description = "Reason for refund")
        String reason
) {
}
