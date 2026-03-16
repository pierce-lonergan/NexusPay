package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Tokenize a payment method from the checkout SDK")
public record TokenizeRequest(
        @NotBlank @Schema(description = "Payment method type", example = "card")
        String type,

        @Schema(description = "Encrypted payment method data (from PCI iframe)")
        String token_data,

        @Schema(description = "Last four digits of card number", example = "4242")
        String card_last_four,

        @Schema(description = "Card brand", example = "visa")
        String card_brand,

        @Schema(description = "Card expiration month", example = "12")
        Integer card_exp_month,

        @Schema(description = "Card expiration year", example = "2027")
        Integer card_exp_year
) {
}
