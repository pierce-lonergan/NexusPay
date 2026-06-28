package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Create a new payment intent")
public record CreatePaymentRequest(
        @Min(1) @Schema(description = "Amount in minor units (e.g., cents)", example = "5000")
        long amount,

        @NotBlank @Schema(description = "Three-letter ISO 4217 currency code", example = "USD")
        String currency,

        @Schema(description = "Customer identifier")
        String customer_id,

        @Schema(description = "Payment method type", example = "card")
        String payment_method_type,

        @Schema(description = "Payment method data (card details, etc.)")
        String payment_method_data,

        @Schema(description = "URL to redirect after authentication")
        String return_url,

        @Schema(description = "Payment description")
        String description,

        @Schema(description = "Capture method: automatic or manual", example = "automatic")
        String capture_method,

        @Schema(description = "Convenience alias: true→automatic, false→manual capture. "
                + "Ignored when capture_method is supplied.", example = "true")
        Boolean capture,

        @Schema(description = "Arbitrary key-value metadata")
        Map<String, Object> metadata,

        // TEST-3c (off-session charge of a SAVED method). All four are OPTIONAL and backward-compatible:
        // an existing create that omits them binds byte-identically (JSON binding is by-name and Spring's
        // FAIL_ON_UNKNOWN is false). The DTO is @JsonInclude(NON_NULL), so an absent field is omitted on
        // both read and (echoed) write.
        @Schema(description = "A saved payment-method id (pm_...) to charge off-session. When present, the "
                + "create resolves this tenant-owned saved method instead of taking an inline-card path.",
                example = "pm_1a2b3c")
        String payment_method,

        @Schema(description = "Whether this is an off-session charge (the cardholder is NOT present). "
                + "Threaded to the PSP as a charge hint.", example = "true")
        Boolean off_session,

        @Schema(description = "Indicates intended future usage of the payment method.",
                example = "off_session", allowableValues = {"off_session", "on_session"})
        String setup_future_usage,

        @Schema(description = "TEST-3d: a cited mandate id (mandate_*) is a VALIDATED CONSENT GATE for an "
                + "off-session charge. When present it must be an ACTIVE mandate for the caller's tenant "
                + "authorizing the charged payment_method (pm_*): a foreign/missing mandate (or payment_method) "
                + "-> 404 no-oracle; a non-ACTIVE mandate -> 400 invalid_mandate; a mandate authorizing a "
                + "different pm_ -> 400 mandate_payment_method_mismatch. The gateway is never reached on any of "
                + "these (no money moves). A null/absent mandate_id is the 3c pass-through.")
        String mandate_id
) {
}
