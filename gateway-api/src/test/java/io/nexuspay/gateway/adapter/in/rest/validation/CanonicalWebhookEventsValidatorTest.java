package io.nexuspay.gateway.adapter.in.rest.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INT-1: unit tests for {@link CanonicalWebhookEventsValidator}. Canonical dotted names and {@code "*"}
 * are valid; the old non-canonical examples ({@code payment.captured}/{@code refund.completed}) and any
 * bogus name are rejected. {@code @NotEmpty} owns the empty/null case, so those are valid here.
 */
class CanonicalWebhookEventsValidatorTest {

    private final CanonicalWebhookEventsValidator validator = new CanonicalWebhookEventsValidator();

    private boolean valid(List<String> events) {
        // The validator does not use the context on the valid path; null is acceptable for these cases.
        return validator.isValid(events, null);
    }

    @Test
    void canonicalNames_areValid() {
        assertThat(valid(List.of("payment.succeeded", "payment.refunded"))).isTrue();
        assertThat(valid(List.of("payment.created", "payment.authorized", "payment.failed",
                "payment.canceled", "payment.refund.created", "payment.refund.failed"))).isTrue();
    }

    @Test
    void wildcard_isValid() {
        assertThat(valid(List.of("*"))).isTrue();
    }

    @Test
    void oldNonCanonicalExamples_areInvalid() {
        assertThat(valid(List.of("payment.captured"))).isFalse();
        assertThat(valid(List.of("refund.completed"))).isFalse();
    }

    @Test
    void bogusName_isInvalid() {
        assertThat(valid(List.of("bogus.event"))).isFalse();
    }

    @Test
    void oneBadAmongGood_isInvalid() {
        assertThat(valid(List.of("payment.succeeded", "bogus.event"))).isFalse();
    }

    @Test
    void emptyOrNull_isValid_notEmptyOwnsThatCase() {
        assertThat(valid(List.of())).isTrue();
        assertThat(valid(null)).isTrue();
    }
}
