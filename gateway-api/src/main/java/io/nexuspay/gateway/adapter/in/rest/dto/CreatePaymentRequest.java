package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Create a new payment intent")
public record CreatePaymentRequest(
        @Min(1) @Schema(description = "Amount in minor units (e.g., cents)", example = "5000")
        long amount,

        @NotBlank @Schema(description = "Three-letter ISO 4217 currency code", example = "USD")
        String currency,

        @Schema(description = "Customer identifier")
        String customer_id,

        @Schema(description = "Payment method type", example = "card")
        String payment_method_type,

        @Schema(description = "Payment method data (card details, etc.)")
        String payment_method_data,

        @Schema(description = "URL to redirect after authentication")
        String return_url,

        @Schema(description = "Payment description")
        String description,

        @Schema(description = "Capture method: automatic or manual", example = "automatic")
        String capture_method,

        @Schema(description = "Convenience alias: true→automatic, false→manual capture. "
                + "Ignored when capture_method is supplied.", example = "true")
        Boolean capture,

        @Schema(description = "Arbitrary key-value metadata")
        Map<String, Object> metadata
) {
}
