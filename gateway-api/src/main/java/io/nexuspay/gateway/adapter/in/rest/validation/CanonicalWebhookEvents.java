package io.nexuspay.gateway.adapter.in.rest.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * INT-1 Bean Validation constraint for the {@code events} list on a webhook-endpoint registration. Each
 * element must be a canonical dotted event name ({@code io.nexuspay.common.event.WebhookEventTaxonomy})
 * or the {@code "*"} wildcard; an unknown name is rejected so a merchant can never subscribe to a name
 * the delivery serializer would not emit.
 *
 * <p>A violation surfaces as {@code MethodArgumentNotValidException} → HTTP 400 via
 * {@code GlobalExceptionHandler}, the same path {@code @SafeWebhookUrl} uses. {@code @NotEmpty} owns the
 * empty-list case; this constraint treats a null/empty list as valid (no duplicate violation).</p>
 */
@Documented
@Constraint(validatedBy = CanonicalWebhookEventsValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface CanonicalWebhookEvents {

    String message() default "events contains an unknown webhook event type; allowed: canonical dotted "
            + "names (e.g. payment.succeeded, payment.refunded) or \"*\"";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
