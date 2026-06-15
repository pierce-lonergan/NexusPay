package io.nexuspay.gateway.adapter.in.rest.validation;

import io.nexuspay.common.net.WebhookUrlValidationException;
import io.nexuspay.common.net.WebhookUrlValidator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * ConstraintValidator backing {@link SafeWebhookUrl} (SEC-14). Delegates entirely to the shared
 * {@link WebhookUrlValidator} so registration and delivery enforce identical SSRF rules.
 *
 * <p>On rejection it replaces the default constraint message with the validator's specific reason
 * (e.g. "must use https", "resolves to a non-public address") so the 400 response is actionable
 * while still being a Bean Validation failure (→ {@code MethodArgumentNotValidException}).</p>
 */
public class SafeWebhookUrlValidator implements ConstraintValidator<SafeWebhookUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // @NotBlank handles null/blank separately; treat absent value as valid here to avoid a
        // duplicate violation message (let @NotBlank own that case).
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            WebhookUrlValidator.validateAndResolve(value);
            return true;
        } catch (WebhookUrlValidationException e) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(e.getMessage()).addConstraintViolation();
            return false;
        }
    }
}
