package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * TEST-4a (F2): the recomputed HMAC signature for ONE webhook delivery the caller OWNS
 * ({@code GET /v1/webhook-deliveries/{id}/signature}).
 *
 * <p>Returns ONLY the {@code algorithm} + the hex {@code signature} + the owning {@code endpoint_id}. The
 * signing SECRET is NEVER included (not even masked) — it is read transiently from {@code webhook_endpoints}
 * solely to recompute the HMAC and is then discarded.</p>
 *
 * <p><strong>Rotated-secret caveat:</strong> the signature is recomputed using the endpoint's CURRENT
 * secret (exactly as {@code WebhookDeliveryService.send} reads {@code getSecret()} live per attempt). If the
 * secret was ROTATED AFTER the original delivery, this recomputed signature DIFFERS from the
 * originally-delivered {@code X-NexusPay-Signature} header — it is not proof the original delivery was
 * mis-signed. The {@code rotated_secret_caveat} field carries this note in-band. snake_case component names
 * per L-072.</p>
 *
 * @since TEST-4a
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Recomputed HMAC signature for a webhook delivery the caller owns (never the secret)")
public record WebhookDeliverySignatureResponse(
        @Schema(description = "Delivery id", example = "whd_abc123")
        String id,

        @Schema(description = "Owning webhook endpoint id", example = "we_abc123")
        String endpoint_id,

        @Schema(description = "Signature algorithm", example = "HmacSHA256")
        String algorithm,

        @Schema(description = "Hex HMAC-SHA256 of canonical_body keyed by the endpoint's CURRENT secret")
        String signature,

        @Schema(description = "Caveat: recompute uses the CURRENT secret; differs from the original "
                + "delivery's signature if the secret was rotated after delivery")
        String rotated_secret_caveat
) {
}
