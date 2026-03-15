package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Journal entry with postings")
public record JournalEntryResponse(
        @Schema(description = "Journal entry ID")
        String id,

        @Schema(description = "Reference to the originating payment")
        String payment_reference,

        @Schema(description = "Description of the entry")
        String description,

        @Schema(description = "When the entry was posted")
        Instant posted_at,

        @Schema(description = "Arbitrary metadata")
        Map<String, Object> metadata,

        @Schema(description = "Debit/credit postings")
        List<PostingResponse> postings
) {

    @Schema(description = "A single debit or credit line")
    public record PostingResponse(
            String id,
            String ledger_account_id,
            long amount,
            String currency
    ) {
    }
}
