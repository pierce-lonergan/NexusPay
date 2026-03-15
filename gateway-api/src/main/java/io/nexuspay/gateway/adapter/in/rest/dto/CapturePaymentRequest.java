package io.nexuspay.gateway.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Capture a previously authorized payment")
public record CapturePaymentRequest(
        @Schema(description = "Amount to capture in minor units. Omit to capture the full amount.")
        Long amount_to_capture
) {
}
