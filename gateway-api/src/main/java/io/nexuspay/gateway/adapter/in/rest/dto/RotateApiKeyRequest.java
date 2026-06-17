package io.nexuspay.gateway.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(description = "Rotate an API key with an overlap window during which the old key still works.")
public record RotateApiKeyRequest(
        // DX-5c: overlap window in seconds. Optional: null => server DEFAULT (24h). The controller
        // CLAMPS to a hard cap (7 days) — an unbounded client value is never trusted. Zero retires the
        // old key immediately. @PositiveOrZero rejects a negative value early.
        @PositiveOrZero
        @Schema(description = "Overlap in seconds the old key keeps working (default 86400, capped at 604800). "
                + "Zero retires the old key immediately.",
                example = "86400")
        Long overlapSeconds
) {
}
