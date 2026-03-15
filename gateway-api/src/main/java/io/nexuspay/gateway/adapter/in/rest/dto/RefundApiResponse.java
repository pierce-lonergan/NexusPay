package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Refund object")
public record RefundApiResponse(
        @Schema(description = "Refund ID from the gateway")
        String id,

        @Schema(description = "Parent payment ID")
        String payment_id,

        @Schema(description = "Refund status")
        String status,

        @Schema(description = "Amount in minor units")
        long amount,

        @Schema(description = "Three-letter ISO 4217 currency code")
        String currency,

        @Schema(description = "Reason for refund")
        String reason,

        @Schema(description = "Connector that processed the refund")
        String connector,

        @Schema(description = "Error code if refund failed")
        String error_code,

        @Schema(description = "Error message if refund failed")
        String error_message,

        @Schema(description = "When the refund was created")
        Instant created_at
) {
}
