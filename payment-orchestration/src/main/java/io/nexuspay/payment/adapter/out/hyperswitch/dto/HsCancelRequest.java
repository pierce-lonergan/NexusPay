package io.nexuspay.payment.adapter.out.hyperswitch.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HsCancelRequest(
        @JsonProperty("cancellation_reason") String cancellationReason
) {
}
