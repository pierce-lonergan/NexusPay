package io.nexuspay.payment.adapter.out.hyperswitch.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HsRefundResponse(
        @JsonProperty("refund_id") String refundId,
        @JsonProperty("payment_id") String paymentId,
        @JsonProperty("status") String status,
        @JsonProperty("amount") long amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("reason") String reason,
        @JsonProperty("connector") String connector,
        @JsonProperty("connector_refund_id") String connectorRefundId,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("created_at") Instant createdAt
) {
}
