package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * GAP-079 (critique v3 F6): one entry in the {@code GET /v1/test/idempotency-keys} listing — a view of an
 * idempotency key the CALLER owns (scoped to their own Authorization-hash, so only their keys are ever
 * returned).
 *
 * <p>Snake_case wire names pinned explicitly via {@link JsonProperty} (L-072). {@code http_status} is set
 * ONLY for a cached entry (null while still {@code processing}); {@code JsonInclude.NON_NULL} omits it when
 * null.</p>
 *
 * @since GAP-079
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A caller-owned idempotency key (GET /v1/test/idempotency-keys)")
public record IdempotencyKeyView(
        @JsonProperty("key")
        @Schema(description = "The idempotency key value (prefix stripped)", example = "order-1")
        String key,

        @JsonProperty("status")
        @Schema(description = "`processing` (in-flight) or `cached` (a response is stored)", example = "cached")
        String status,

        @JsonProperty("http_status")
        @Schema(description = "The cached response's HTTP status (only present when status=cached)", example = "200")
        Integer httpStatus,

        @JsonProperty("ttl_seconds")
        @Schema(description = "Remaining TTL in seconds (-1 no expiry, -2 missing)", example = "86392")
        long ttlSeconds
) {
}
