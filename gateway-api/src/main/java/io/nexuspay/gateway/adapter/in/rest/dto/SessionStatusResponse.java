package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionStatusResponse(
        String id,
        String status,
        long amount,
        String currency,
        String payment_intent_id,
        List<String> allowed_payment_methods,
        Map<String, Object> next_action
) {
}
