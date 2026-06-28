package io.nexuspay.gateway.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * TEST-5 (E3): response for {@code GET /v1/ping}.
 *
 * <p>A lightweight authenticated connectivity + credentials check: a 200 confirms the API key is valid and
 * the platform is reachable. {@code livemode} reflects the AUTHENTICATED KEY'S MODE (test key -> {@code
 * false}, live key -> {@code true}) so an integrator can confirm they are pointed at test vs live;
 * {@code api_version} is the single canonical contract version. The body carries NO tenant id and nothing
 * sensitive (no scopes, no key, no principal detail) — it cannot leak tenant identity.</p>
 *
 * <p>L-072: snake_case wire component {@code api_version} (the other components are already
 * snake_case-safe single words).</p>
 *
 * @since TEST-5
 */
@Schema(description = "A connectivity + credentials check result (GET /v1/ping)")
public record PingResponse(
        @Schema(description = "Always true on a 200 — the key is valid and the platform is reachable")
        boolean ok,

        @Schema(description = "The authenticated key's mode: test key -> false, live key -> true")
        boolean livemode,

        @Schema(description = "The canonical API contract version (date-based)", example = "2026-06-16")
        String api_version
) {
}
