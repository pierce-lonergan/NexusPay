package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Create a payment session for the checkout SDK")
public record CreatePaymentSessionRequest(
        @Min(1) @Schema(description = "Amount in minor units (e.g., cents)", example = "5000")
        long amount,

        @NotBlank @Schema(description = "Three-letter ISO 4217 currency code", example = "USD")
        String currency,

        @Schema(description = "Customer identifier")
        String customer_id,

        @Schema(description = "URL to redirect on successful payment")
        String success_url,

        @Schema(description = "URL to redirect on cancelled payment")
        String cancel_url,

        @Schema(description = "Allowed payment methods", example = "[\"card\"]")
        List<String> allowed_payment_methods,

        @Schema(description = "Merchant branding for hosted checkout (logo_url, accent_color, etc.)")
        Map<String, Object> branding,

        @Schema(description = "Arbitrary key-value metadata")
        Map<String, Object> metadata
) {
}
