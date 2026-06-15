package io.nexuspay.gateway.adapter.in.rest.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean Validation constraint (SEC-14) for merchant-supplied webhook target URLs.
 *
 * <p>Delegates to the shared {@code io.nexuspay.common.net.WebhookUrlValidator}: requires https and
 * rejects any host resolving to a loopback/private/link-local/ULA/CGNAT/multicast/cloud-metadata
 * address. A failure surfaces as {@code MethodArgumentNotValidException} → HTTP 400 via
 * {@code GlobalExceptionHandler}, satisfying the registration-time SSRF gate. This is the FIRST gate;
 * delivery-time re-validation in {@code WebhookDeliveryService} is the SECOND (anti-DNS-rebinding).</p>
 */
@Documented
@Constraint(validatedBy = SafeWebhookUrlValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeWebhookUrl {

    String message() default "must be a public https URL (internal/loopback/link-local/metadata targets are not allowed)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
