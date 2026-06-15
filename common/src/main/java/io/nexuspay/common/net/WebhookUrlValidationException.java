package io.nexuspay.common.net;

/**
 * Thrown by {@link WebhookUrlValidator} when a merchant-supplied webhook URL fails the SSRF guard
 * (SEC-14): malformed, non-https scheme, unresolvable host, or resolving to any non-public/special
 * address (loopback, private, link-local, ULA, CGNAT, multicast, cloud-metadata).
 *
 * <p>At REGISTRATION this is wrapped by the {@code @SafeWebhookUrl} ConstraintValidator into a Bean
 * Validation failure → {@code MethodArgumentNotValidException} → HTTP 400. At DELIVERY it is caught
 * by {@code WebhookDeliveryService}'s per-endpoint try/catch, which logs and skips the endpoint so
 * no server-side request is ever made to the internal target.</p>
 */
public class WebhookUrlValidationException extends RuntimeException {

    public WebhookUrlValidationException(String message) {
        super(message);
    }
}
