package io.nexuspay.payment.adapter.out.hyperswitch.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * HyperSwitch POST /payments request body.
 * Maps NexusPay domain objects to HyperSwitch's API contract.
 *
 * @see <a href="https://api-reference.hyperswitch.io/api-reference/payments/payments--create">HyperSwitch Payments API</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HsPaymentCreateRequest(
        @JsonProperty("amount") long amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("customer_id") String customerId,
        @JsonProperty("capture_method") String captureMethod,
        @JsonProperty("description") String description,
        @JsonProperty("return_url") String returnUrl,
        @JsonProperty("metadata") Map<String, Object> metadata
) {
}
