package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentSessionResponse(
        String id,
        String status,
        long amount,
        String currency,
        String customer_id,
        String payment_intent_id,
        String client_secret,
        List<String> allowed_payment_methods,
        String success_url,
        String cancel_url,
        Map<String, Object> branding,
        Instant expires_at,
        Instant created_at
) {
}
