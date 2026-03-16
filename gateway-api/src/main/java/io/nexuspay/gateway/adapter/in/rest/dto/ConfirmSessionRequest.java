package io.nexuspay.gateway.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Confirm a payment session using a tokenized payment method")
public record ConfirmSessionRequest(
        @NotBlank @Schema(description = "ID of the payment token to use for confirmation")
        String payment_token_id
) {
}
