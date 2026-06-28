package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * GAP-077 (critique v3 F4): response for {@code POST /v1/test/sandbox/reset}.
 *
 * <p>The per-table count of TEST rows (tenant + {@code livemode=false} scoped) hard-deleted by the reset.
 * Snake_case wire names are pinned explicitly via {@link JsonProperty} (L-072) so no global naming strategy
 * is relied upon. The satellite/log tables are deliberately excluded from the reset (see
 * {@code SandboxResetService}), so they carry no field here.</p>
 *
 * @since GAP-077
 */
@Schema(description = "Per-table deleted-count summary of a sandbox reset (POST /v1/test/sandbox/reset)")
public record SandboxResetResponse(
        @JsonProperty("payments")
        @Schema(description = "Test payment projection rows deleted", example = "12")
        long payments,

        @JsonProperty("refunds")
        @Schema(description = "Test refund projection rows deleted", example = "3")
        long refunds,

        @JsonProperty("customers")
        @Schema(description = "Test customer rows deleted", example = "5")
        long customers,

        @JsonProperty("payment_methods")
        @Schema(description = "Test payment-method rows deleted", example = "7")
        long paymentMethods,

        @JsonProperty("mandates")
        @Schema(description = "Test mandate rows deleted", example = "2")
        long mandates
) {
}
