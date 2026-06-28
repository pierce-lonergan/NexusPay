package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * GAP-078 (critique v3 F5): state of the caller tenant's TEST CLOCK ({@code PUT/GET/DELETE /v1/test/clock}).
 *
 * <p>{@code now} is the instant the clock currently resolves to: the FROZEN instant when {@code frozen} is
 * true, else the live {@code Instant.now()}. {@code frozen} is true exactly when a clock row exists for the
 * tenant.</p>
 *
 * <p>HONEST SCOPE: a frozen clock controls ONLY the {@code created_at} stamped on TEST-created
 * payment/refund artifacts (and, via the GAP-076 read-model inheriting that timestamp, the
 * {@code GET /v1/payments}|{@code /v1/refunds} list ordering). It does NOT control mandate expiry,
 * idempotency-key TTL, webhook retry/backoff, api-key expiry, projection {@code updated_at}, the synthesized
 * webhook envelope timestamp, or ANY live-rail behavior.</p>
 *
 * <p>Snake_case wire names are pinned explicitly via {@link JsonProperty} (L-072).</p>
 *
 * @since GAP-078
 */
@Schema(description = "Caller tenant's test-clock state. A frozen clock controls ONLY the created_at stamped "
        + "on TEST-created payment/refund artifacts (and their list ordering) — NOT expiry, idempotency TTL, "
        + "webhook retry/backoff, updated_at, or any live-rail behavior.")
public record TestClockResponse(
        @JsonProperty("now")
        @Schema(description = "The instant the clock resolves to now (the frozen instant if frozen, else real time)",
                example = "2026-01-01T00:00:00Z")
        Instant now,

        @JsonProperty("frozen")
        @Schema(description = "True when a frozen clock is set for the tenant; false = real time", example = "true")
        boolean frozen
) {
}
