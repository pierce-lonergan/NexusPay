package io.nexuspay.payment.adapter.out.hyperswitch.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HsRefundCreateRequest(
        @JsonProperty("payment_id") String paymentId,
        @JsonProperty("amount") long amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("reason") String reason
) {
}
