package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * Gateway API payment response DTO.
 * Wraps payment-orchestration's PaymentResponse into the public API shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Payment object")
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
        Map<String, Object> metadata
) {
}
