package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Ledger account with balance")
public record LedgerAccountResponse(
        @Schema(description = "Account ID")
        String id,

        @Schema(description = "Account name")
        String name,

        @Schema(description = "Account type: ASSET, LIABILITY, REVENUE, EXPENSE")
        String type,

        @Schema(description = "ISO 4217 currency code")
        String currency,

        @Schema(description = "Posted balance in minor units")
        long posted_balance,

        @Schema(description = "When the account was created")
        Instant created_at,

        @Schema(description = "When the balance was last updated")
        Instant updated_at
) {
}
