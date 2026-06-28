package io.nexuspay.gateway.adapter.out.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * TEST-4a: the SINGLE source of the outbound-webhook HMAC routine.
 *
 * <p>The HMAC-SHA256-over-the-canonical-body-bytes, hex-encoded algorithm is extracted verbatim from
 * {@code WebhookDeliveryService.computeSignature} so that BOTH the delivery sender ({@code send}) and the
 * F2 signature-inspection endpoint compute the EXACT same bytes — the algorithm can never be forked. The
 * X-NexusPay-Signature header a merchant verifies and the signature the F2 endpoint recomputes are produced
 * by this one method.</p>
 *
 * <p>Stateless and side-effect free; logs NOTHING (so the secret bytes never reach a log). The signing
 * secret is supplied per call and is never retained.</p>
 *
 * @since TEST-4a
 */
public final class WebhookSignature {

    /** The HMAC algorithm name surfaced in the F2 signature response (and used by {@link Mac}). */
    public static final String ALGORITHM = "HmacSHA256";

    private WebhookSignature() {
    }

    /**
     * Computes the hex-encoded HMAC-SHA256 of {@code canonicalBody} keyed by {@code secret}, over the
     * UTF-8 bytes of each — byte-identical to what {@code WebhookDeliveryService.send} posts in the
     * {@code X-NexusPay-Signature} header.
     *
     * @param canonicalBody the exact delivered envelope bytes
     * @param secret        the endpoint signing secret (NEVER logged or returned by callers)
     * @return the lowercase hex signature, or {@code ""} if the JCA provider rejects the inputs (mirrors
     *         the prior {@code computeSignature} fail-soft behaviour)
     */
    public static String sign(String canonicalBody, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] hash = mac.doFinal(canonicalBody.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // Fail soft (mirrors the original WebhookDeliveryService.computeSignature): never throw the
            // secret/body into a stack trace. Deliberately logs nothing — the secret must never reach a log.
            return "";
        }
    }
}
