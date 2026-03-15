package io.nexuspay.payment.adapter.out.hyperswitch.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HsConfirmRequest(
        @JsonProperty("payment_method") String paymentMethod,
        @JsonProperty("payment_method_type") String paymentMethodType,
        @JsonProperty("payment_method_data") Object paymentMethodData,
        @JsonProperty("return_url") String returnUrl
) {
}
