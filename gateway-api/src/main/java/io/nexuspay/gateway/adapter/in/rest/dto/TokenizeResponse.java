package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenizeResponse(
        String id,
        String type,
        String card_last_four,
        String card_brand,
        Instant expires_at
) {
}
