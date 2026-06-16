package io.nexuspay.gateway.adapter.in.rest.validation;

import io.nexuspay.common.event.WebhookEventTaxonomy;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;

/**
 * INT-1 {@link ConstraintValidator} backing {@link CanonicalWebhookEvents}. Every element of the
 * subscription list must satisfy {@link WebhookEventTaxonomy#isValid(String)} (a canonical dotted name
 * or {@code "*"}). A null/empty list is treated as valid here — {@code @NotEmpty} owns that case.
 */
public class CanonicalWebhookEventsValidator
        implements ConstraintValidator<CanonicalWebhookEvents, List<String>> {

    @Override
    public boolean isValid(List<String> value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true; // let @NotEmpty own the empty/null case (no duplicate violation)
        }
        for (String event : value) {
            if (!WebhookEventTaxonomy.isValid(event)) {
                return false;
            }
        }
        return true;
    }
}
