package io.nexuspay.payment.adapter.out.hyperswitch.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * HyperSwitch payment response structure.
 * Only maps the fields NexusPay consumes — HyperSwitch responses contain many
 * additional fields that we intentionally ignore for loose coupling.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HsPaymentResponse(
        @JsonProperty("payment_id") String paymentId,
        @JsonProperty("status") String status,
        @JsonProperty("amount") long amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("capture_method") String captureMethod,
        @JsonProperty("customer_id") String customerId,
        @JsonProperty("connector") String connector,
        @JsonProperty("connector_transaction_id") String connectorTransactionId,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("created") Instant created,
        @JsonProperty("metadata") Map<String, Object> metadata
) {
}
